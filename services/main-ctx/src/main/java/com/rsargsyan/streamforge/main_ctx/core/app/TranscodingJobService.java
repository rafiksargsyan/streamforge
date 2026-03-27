package com.rsargsyan.streamforge.main_ctx.core.app;

import com.rsargsyan.streamforge.main_ctx.Config;
import com.rsargsyan.streamforge.main_ctx.core.Util;
import com.rsargsyan.streamforge.main_ctx.core.app.dto.TranscodingJobCreationDTO;
import com.rsargsyan.streamforge.main_ctx.core.app.dto.TranscodingJobDTO;
import com.rsargsyan.streamforge.main_ctx.core.domain.aggregate.TranscodingJob;
import com.rsargsyan.streamforge.main_ctx.core.domain.valueobject.FailureReason;
import com.rsargsyan.streamforge.main_ctx.core.exception.ResourceNotFoundException;
import com.rsargsyan.streamforge.main_ctx.core.exception.VideoFileTooLargeException;
import com.rsargsyan.streamforge.main_ctx.core.exception.VideoNotAccessibleException;
import com.rsargsyan.streamforge.main_ctx.core.ports.repository.AccountRepository;
import com.rsargsyan.streamforge.main_ctx.core.ports.repository.TranscodingJobRepository;
import io.hypersistence.tsid.TSID;
import jakarta.transaction.Transactional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
public class TranscodingJobService {

  private final ApplicationEventPublisher applicationEventPublisher;
  private final TranscodingJobRepository transcodingJobRepository;
  private final AccountRepository accountRepository;
  private final S3Presigner s3Presigner;
  private final Config config;
  private final TransactionTemplate transactionTemplate;
  private final RabbitTemplate rabbitTemplate;
  private final ScheduledExecutorService heartbeatScheduler = Executors.newSingleThreadScheduledExecutor();
  private final ConcurrentHashMap<Long, ScheduledFuture<?>> activeHeartbeats = new ConcurrentHashMap<>();
  private final ConcurrentHashMap<Long, Process> activeProcesses = new ConcurrentHashMap<>();
  private final ScheduledExecutorService cancellationChecker = Executors.newSingleThreadScheduledExecutor();
  private final ScheduledExecutorService outputFolderCleaner = Executors.newSingleThreadScheduledExecutor();
  private final ExecutorService processingExecutor = Executors.newVirtualThreadPerTaskExecutor();
  private Semaphore processingSemaphore;

  @Autowired
  public TranscodingJobService(
      TranscodingJobRepository transcodingJobRepository,
      ApplicationEventPublisher applicationEventPublisher,
      AccountRepository accountRepository,
      S3Presigner s3Presigner,
      Config config,
      TransactionTemplate transactionTemplate,
      RabbitTemplate rabbitTemplate
  ) {
    this.transcodingJobRepository = transcodingJobRepository;
    this.applicationEventPublisher = applicationEventPublisher;
    this.accountRepository = accountRepository;
    this.s3Presigner = s3Presigner;
    this.config = config;
    this.transactionTemplate = transactionTemplate;
    this.rabbitTemplate = rabbitTemplate;
    this.processingSemaphore = new Semaphore(config.processingPoolSize);
    this.cancellationChecker.scheduleAtFixedRate(this::checkCancellations, 3, 3, TimeUnit.SECONDS);
    this.outputFolderCleaner.scheduleAtFixedRate(this::cleanOutputFolder, 1, 1, TimeUnit.HOURS);
  }

  public void acquireSlot() {
    try {
      processingSemaphore.acquire();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  public void releaseSlot() {
    processingSemaphore.release();
  }

  public void submit(String strId, Runnable ack) {
    try {
      receive(strId);
    } catch (Exception e) {
      processingSemaphore.release();
      throw e;
    }
    ack.run();
    Long jobId = TSID.from(strId).toLong();
    startHeartbeat(jobId);
    processingExecutor.submit(() -> {
      try {
        process(jobId);
      } finally {
        processingSemaphore.release();
      }
    });
  }

  public Page<TranscodingJobDTO> findAll(String accountId, int page, int size) {
    var pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
    return transcodingJobRepository.findByAccountId(Util.validateTSID(accountId), pageable)
        .map(job -> TranscodingJobDTO.from(job, dashManifestUrl(job), hlsManifestUrl(job)));
  }

  public TranscodingJobDTO findById(String accountId, String jobId) {
    var job = transcodingJobRepository
        .findByAccountIdAndId(Util.validateTSID(accountId), Util.validateTSID(jobId))
        .orElseThrow(ResourceNotFoundException::new);
    return TranscodingJobDTO.from(job, dashManifestUrl(job), hlsManifestUrl(job));
  }

  public long getMaxFileSizeBytes() {
    return config.maxVideoFileSizeBytes;
  }

  @Transactional
  public void cancel(String accountId, String jobId) {
    var job = transcodingJobRepository
        .findByAccountIdAndId(Util.validateTSID(accountId), Util.validateTSID(jobId))
        .orElseThrow(ResourceNotFoundException::new);
    job.cancel();
    transcodingJobRepository.save(job);
  }

  @Transactional
  public TranscodingJobDTO create(String accountId, TranscodingJobCreationDTO dto) {
    var accountLongId = Util.validateTSID(accountId);
    var account = accountRepository.findById(accountLongId)
        .orElseThrow(ResourceNotFoundException::new);
    var job = new TranscodingJob(account, dto.getVideoURL(), dto.getSpec());
    transcodingJobRepository.save(job);
    applicationEventPublisher.publishEvent(new TranscodingJobCreatedEvent(job.getId()));
    return TranscodingJobDTO.from(job, null, null);
  }

  @Transactional
  public void receive(String id) {
    transcodingJobRepository.findById(Util.validateTSID(id)).ifPresentOrElse(
        job -> {
          job.receive();
          transcodingJobRepository.save(job);
          applicationEventPublisher.publishEvent(new TranscodingJobReceivedEvent(job.getId()));
        },
        () -> { throw new ResourceNotFoundException(); }
    );
  }

  @Transactional
  public void retryStuckJobs() {
    Instant threshold = Instant.now().minusSeconds(config.staleHeartbeatSeconds);
    List<TranscodingJob> stuckJobs = transcodingJobRepository.findStuckJobs(threshold);
    for (TranscodingJob job : stuckJobs) {
      if (job.getRetryCount() >= config.maxRetries) {
        log.warn("[{}] Job exceeded max retries ({}), marking as FAILURE", job.getStrId(), config.maxRetries);
        job.fail(FailureReason.UNKNOWN);
      } else {
        log.warn("[{}] Retrying stuck job (attempt {})", job.getStrId(), job.getRetryCount() + 1);
        Process stuckProcess = activeProcesses.remove(job.getId());
        if (stuckProcess != null) stuckProcess.destroyForcibly();
        job.retry();
        applicationEventPublisher.publishEvent(new TranscodingJobRetryEvent(job.getId()));
      }
      transcodingJobRepository.save(job);
    }

    Instant mqConfirmThreshold = Instant.now().minusSeconds(30);
    for (TranscodingJob job : transcodingJobRepository.findStuckQueuedJobs(mqConfirmThreshold)) {
      log.warn("[{}] QUEUED job unconfirmed for >30s, resending to RabbitMQ", job.getStrId());
      try {
        transcodingJobRepository.updateMqSent(job.getId(), Instant.now());
        sendToRabbitMq(job.getStrId());
      } catch (Exception e) {
        log.warn("[{}] Failed to resend to RabbitMQ: {}", job.getStrId(), e.getMessage());
      }
    }
  }

  void startHeartbeat(Long jobId) {
    ScheduledFuture<?> heartbeat = heartbeatScheduler.scheduleAtFixedRate(
        () -> {
          try {
            transcodingJobRepository.updateHeartbeat(jobId, Instant.now());
          } catch (Exception e) {
            log.warn("[{}] Heartbeat update failed: {}", TSID.from(jobId), e.getMessage());
          }
        },
        0,
        config.heartbeatIntervalSeconds,
        TimeUnit.SECONDS
    );
    activeHeartbeats.put(jobId, heartbeat);
  }

  void process(Long jobId) {
    ScheduledFuture<?> heartbeat = activeHeartbeats.get(jobId);
    Path jobFolder = null;
    Path videoFile = null;
    final String strId = TSID.from(jobId).toString();

    try {
      var job = transactionTemplate.execute(status -> {
        var j = transcodingJobRepository.findById(jobId)
            .orElseThrow(ResourceNotFoundException::new);
        j.run();
        transcodingJobRepository.save(j);
        return j;
      });
      if (job == null) return;

      String attemptKey = strId + "-" + job.getRetryCount();
      jobFolder = Paths.get(config.baseOutputFolder).resolve(attemptKey);
      Files.createDirectories(jobFolder);

      String videoUrl = job.getVideoURL().toString();
      checkVideoAccessibility(videoUrl);

      videoFile = jobFolder.getParent().resolve(attemptKey + ".video");
      log.info("[{}] Downloading video: {}", strId, videoUrl);
      long downloadT = System.nanoTime();
      downloadVideo(videoUrl, videoFile);
      log.info("[{}] Download: {}s", strId, elapsed(downloadT));

      log.info("[{}] Starting transcoding", strId);
      long transcodeT = System.nanoTime();
      try {
        VideoTranscoder.transcode(videoFile.toString(), jobFolder, job.getSpec(),
            config.ffmpegThreads, p -> activeProcesses.put(jobId, p));
      } catch (Exception e) {
        throw new JobFailureException(FailureReason.PROCESSING_FAILED, e);
      }
      log.info("[{}] Transcoding: {}s", strId, elapsed(transcodeT));

      log.info("[{}] Uploading output to S3", strId);
      long uploadT = System.nanoTime();
      try {
        awsS3Upload(jobFolder, strId + "/", true);
      } catch (Exception e) {
        throw new JobFailureException(FailureReason.UPLOAD_FAILED, e);
      }
      log.info("[{}] Upload: {}s", strId, elapsed(uploadT));

      transactionTemplate.executeWithoutResult(status -> {
        var j = transcodingJobRepository.findById(jobId).orElseThrow(ResourceNotFoundException::new);
        j.succeed();
        transcodingJobRepository.save(j);
      });

    } catch (Exception e) {
      boolean cancelled = transcodingJobRepository.findById(jobId)
          .map(j -> j.getStatus() == TranscodingJob.Status.CANCELLED)
          .orElse(false);
      if (cancelled) {
        log.info("[{}] Job was cancelled", strId);
      } else {
        log.error("[{}] Processing failed: {}", strId, e.getMessage(), e);
        FailureReason reason = toFailureReason(e);
        boolean retryable = reason == FailureReason.UPLOAD_FAILED || reason == FailureReason.PROCESSING_FAILED;
        if (reason == FailureReason.VIDEO_NOT_ACCESSIBLE && !isNetworkReachable()) {
          log.warn("[{}] Video not accessible but network is down — will retry", strId);
          retryable = true;
        }
        try {
          final boolean doRetry = retryable;
          transactionTemplate.executeWithoutResult(status -> {
            var j = transcodingJobRepository.findById(jobId).orElseThrow(ResourceNotFoundException::new);
            if (doRetry && j.getRetryCount() < config.maxRetries) {
              log.warn("[{}] {} failed, scheduling retry (attempt {})", strId, reason, j.getRetryCount() + 1);
              j.retry();
              transcodingJobRepository.save(j);
              applicationEventPublisher.publishEvent(new TranscodingJobRetryEvent(jobId));
            } else {
              j.fail(reason);
              transcodingJobRepository.save(j);
            }
          });
        } catch (Exception saveException) {
          log.error("[{}] Failed to persist failure status", strId, saveException);
        }
      }
    } finally {
      activeHeartbeats.remove(jobId, heartbeat);
      activeProcesses.remove(jobId);
      if (heartbeat != null) heartbeat.cancel(false);
      if (jobFolder != null) deleteRecursively(jobFolder);
      try { if (videoFile != null) Files.deleteIfExists(videoFile); } catch (IOException ignored) {}
    }
  }

  private void sendToRabbitMq(String strId) {
    rabbitTemplate.convertAndSend(config.topicExchangeName, config.routingKey, strId, m -> {
      m.getMessageProperties().setDeliveryMode(MessageDeliveryMode.PERSISTENT);
      return m;
    }, new CorrelationData(strId));
  }

  private void checkCancellations() {
    if (activeProcesses.isEmpty()) return;
    try {
      transcodingJobRepository.findAllById(activeProcesses.keySet()).forEach(job -> {
        if (job.getStatus() == TranscodingJob.Status.CANCELLED) {
          Process process = activeProcesses.get(job.getId());
          if (process != null) {
            log.info("[{}] Cancelling active process", job.getStrId());
            process.destroyForcibly();
          }
        }
      });
    } catch (Exception e) {
      log.warn("Error in cancellation checker: {}", e.getMessage());
    }
  }

  private void cleanOutputFolder() {
    Path root = Paths.get(config.baseOutputFolder);
    if (!Files.exists(root)) return;
    Instant cutoff = Instant.now().minus(Duration.ofDays(1));
    try (var stream = Files.list(root)) {
      stream.filter(Files::isDirectory).forEach(dir -> {
        try {
          if (Files.getLastModifiedTime(dir).toInstant().isBefore(cutoff)) {
            deleteRecursively(dir);
            log.info("Cleaned up stale output folder: {}", dir.getFileName());
          }
        } catch (Exception e) {
          log.warn("Failed to clean output folder {}: {}", dir.getFileName(), e.getMessage());
        }
      });
    } catch (Exception e) {
      log.warn("Output folder cleanup failed: {}", e.getMessage());
    }
  }

  private void checkVideoAccessibility(String videoUrl) {
    try {
      var client = HttpClient.newBuilder()
          .followRedirects(HttpClient.Redirect.NORMAL)
          .connectTimeout(Duration.ofSeconds(15))
          .build();
      var request = HttpRequest.newBuilder()
          .method("HEAD", HttpRequest.BodyPublishers.noBody())
          .uri(URI.create(videoUrl))
          .timeout(Duration.ofSeconds(30))
          .build();
      var response = client.send(request, HttpResponse.BodyHandlers.discarding());
      int status = response.statusCode();
      if (status >= 400 && status != 405) {
        throw new VideoNotAccessibleException("Video returned HTTP " + status + ": " + videoUrl);
      }
      response.headers().firstValueAsLong("content-length").ifPresent(size -> {
        if (size > config.maxVideoFileSizeBytes) {
          throw new VideoFileTooLargeException(size, config.maxVideoFileSizeBytes);
        }
      });
    } catch (VideoNotAccessibleException | VideoFileTooLargeException e) {
      throw e;
    } catch (Exception ignored) {
    }
  }

  private static void downloadVideo(String videoUrl, Path videoFile) {
    try {
      java.net.HttpURLConnection conn = (java.net.HttpURLConnection) new java.net.URL(videoUrl).openConnection();
      conn.setConnectTimeout(15_000);
      conn.setReadTimeout(30_000);
      conn.setInstanceFollowRedirects(true);
      int status = conn.getResponseCode();
      if (status >= 400) {
        throw new VideoNotAccessibleException("Video returned HTTP " + status + ": " + videoUrl);
      }
      try (var in = conn.getInputStream()) {
        Files.copy(in, videoFile, StandardCopyOption.REPLACE_EXISTING);
      }
    } catch (VideoNotAccessibleException e) {
      throw e;
    } catch (Exception e) {
      throw new VideoNotAccessibleException("Failed to download video: " + videoUrl, e);
    }
  }

  private void awsS3Upload(Path source, String s3Key, boolean recursive) throws Exception {
    ProcessBuilder pb = new ProcessBuilder(
        "aws", "s3", "cp",
        source.toString(),
        "s3://" + config.s3Bucket + "/" + s3Key,
        "--endpoint-url", config.s3Endpoint
    );
    if (recursive) {
      pb.command().add("--recursive");
    }
    pb.environment().put("AWS_ACCESS_KEY_ID", config.s3AccessKeyId);
    pb.environment().put("AWS_SECRET_ACCESS_KEY", config.s3SecretAccessKey);
    pb.environment().put("AWS_DEFAULT_REGION", config.s3Region);
    pb.inheritIO();
    int exitCode = pb.start().waitFor();
    if (exitCode != 0) {
      throw new RuntimeException("aws s3 cp failed with exit code " + exitCode);
    }
  }

  private String dashManifestUrl(TranscodingJob job) {
    return presignedUrl(job, "vod/manifest.mpd");
  }

  private String hlsManifestUrl(TranscodingJob job) {
    return presignedUrl(job, "vod/master.m3u8");
  }

  private String presignedUrl(TranscodingJob job, String filename) {
    if (job.getStatus() != TranscodingJob.Status.SUCCESS) return null;
    Instant expiry = job.getFinishedAt().plus(Duration.ofHours(config.outputUrlTtlHours));
    Instant now = Instant.now();
    if (!now.isBefore(expiry)) return null;
    var presignRequest = GetObjectPresignRequest.builder()
        .signatureDuration(Duration.between(now, expiry))
        .getObjectRequest(GetObjectRequest.builder()
            .bucket(config.s3Bucket)
            .key(job.getStrId() + "/" + filename)
            .build())
        .build();
    return s3Presigner.presignGetObject(presignRequest).url().toString();
  }

  private static boolean isNetworkReachable() {
    try (var socket = new java.net.Socket()) {
      socket.connect(new java.net.InetSocketAddress("8.8.8.8", 53), 3_000);
      return true;
    } catch (Exception e) {
      return false;
    }
  }

  private static void deleteRecursively(Path path) {
    if (!Files.exists(path)) return;
    try (var stream = Files.walk(path)) {
      stream.sorted(Comparator.reverseOrder())
          .forEach(p -> {
            try { Files.delete(p); } catch (IOException ignored) {}
          });
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static long elapsed(long startNano) {
    return TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - startNano);
  }

  private static FailureReason toFailureReason(Exception e) {
    if (e instanceof JobFailureException jfe) return jfe.reason;
    if (e instanceof VideoFileTooLargeException) return FailureReason.VIDEO_TOO_LARGE;
    if (e instanceof VideoNotAccessibleException) return FailureReason.VIDEO_NOT_ACCESSIBLE;
    return FailureReason.UNKNOWN;
  }
}
