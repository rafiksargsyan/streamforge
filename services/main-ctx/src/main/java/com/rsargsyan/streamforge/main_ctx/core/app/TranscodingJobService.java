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
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
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
  private final S3Client s3Client;
  private final S3Presigner s3Presigner;
  private final Config config;
  private final TransactionTemplate transactionTemplate;
  private final RabbitTemplate rabbitTemplate;
  private final ScheduledExecutorService heartbeatScheduler = Executors.newSingleThreadScheduledExecutor();
  private final ConcurrentHashMap<Long, ScheduledFuture<?>> activeHeartbeats = new ConcurrentHashMap<>();
  private final ConcurrentHashMap<Long, Process> activeProcesses = new ConcurrentHashMap<>();
  private final ScheduledExecutorService cancellationChecker = Executors.newSingleThreadScheduledExecutor();
  private final ScheduledExecutorService outputFolderCleaner = Executors.newSingleThreadScheduledExecutor();
  private final ScheduledExecutorService jobCleaner = Executors.newSingleThreadScheduledExecutor();
  private final ExecutorService processingExecutor = Executors.newVirtualThreadPerTaskExecutor();
  private Semaphore processingSemaphore;

  @Autowired
  public TranscodingJobService(
      TranscodingJobRepository transcodingJobRepository,
      ApplicationEventPublisher applicationEventPublisher,
      AccountRepository accountRepository,
      S3Client s3Client,
      @Autowired(required = false) S3Presigner s3Presigner,
      Config config,
      TransactionTemplate transactionTemplate,
      RabbitTemplate rabbitTemplate
  ) {
    this.transcodingJobRepository = transcodingJobRepository;
    this.applicationEventPublisher = applicationEventPublisher;
    this.accountRepository = accountRepository;
    this.s3Client = s3Client;
    this.s3Presigner = s3Presigner;
    this.config = config;
    this.transactionTemplate = transactionTemplate;
    this.rabbitTemplate = rabbitTemplate;
    this.processingSemaphore = new Semaphore(config.processingPoolSize);
    this.cancellationChecker.scheduleAtFixedRate(this::checkCancellations, 3, 3, TimeUnit.SECONDS);
    this.outputFolderCleaner.scheduleAtFixedRate(this::cleanOutputFolder, 1, 1, TimeUnit.HOURS);
    this.jobCleaner.scheduleAtFixedRate(this::cleanupJobs, 1, 1, TimeUnit.HOURS);
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
    receive(strId);
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
        .map(job -> TranscodingJobDTO.from(job, downloadUrl(job), expiresAt(job)));
  }

  public TranscodingJobDTO findById(String accountId, String jobId) {
    var job = transcodingJobRepository
        .findByAccountIdAndId(Util.validateTSID(accountId), Util.validateTSID(jobId))
        .orElseThrow(ResourceNotFoundException::new);
    return TranscodingJobDTO.from(job, downloadUrl(job), expiresAt(job));
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
        },
        () -> { throw new ResourceNotFoundException(); }
    );
  }

  @Transactional
  public void retryStuckJobs() {
    Instant threshold = Instant.now().minusSeconds(config.staleHeartbeatSeconds);
    List<TranscodingJob> stuckJobs = transcodingJobRepository.findStuckJobs(threshold);
    for (TranscodingJob job : stuckJobs) {
      transactionTemplate.executeWithoutResult(status -> {
        TranscodingJob j = transcodingJobRepository.findById(job.getId()).orElseThrow();
        if (j.getRetryCount() >= config.maxRetries) {
          log.warn("[{}] Job exceeded max retries ({}), marking as FAILURE", j.getStrId(), config.maxRetries);
          j.fail(FailureReason.UNKNOWN);
          transcodingJobRepository.save(j);
        } else {
          log.warn("[{}] Retrying stuck job (attempt {})", j.getStrId(), j.getRetryCount() + 1);
          Process stuckProcess = activeProcesses.remove(j.getId());
          if (stuckProcess != null) stuckProcess.destroyForcibly();
          j.retry();
          transcodingJobRepository.save(j);
          applicationEventPublisher.publishEvent(new TranscodingJobRetryEvent(j.getId()));
        }
      });
    }

    Instant mqConfirmThreshold = Instant.now().minusSeconds(30);
    for (TranscodingJob job : transcodingJobRepository.findStuckQueuedJobs(mqConfirmThreshold)) {
      transactionTemplate.executeWithoutResult(status -> {
        log.warn("[{}] QUEUED job unconfirmed for >30s, resending to RabbitMQ", job.getStrId());
        transcodingJobRepository.updateMqSent(job.getId(), Instant.now());
        sendToRabbitMq(job.getStrId());
      });
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
    Path zipFile = null;
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

      log.info("[{}] Zipping and uploading output to S3", strId);
      long uploadT = System.nanoTime();
      try {
        zipFile = jobFolder.getParent().resolve(attemptKey + ".zip");
        zipDirectory(jobFolder.resolve("vod"), zipFile);
        awsS3Upload(jobId, zipFile, strId + ".zip", false);
      } catch (Exception e) {
        throw new JobFailureException(FailureReason.UPLOAD_FAILED, e);
      }
      log.info("[{}] Upload: {}s", strId, elapsed(uploadT));

      transactionTemplate.executeWithoutResult(status -> {
        var j = transcodingJobRepository.findById(jobId).orElseThrow(ResourceNotFoundException::new);
        if (j.getStatus() == TranscodingJob.Status.CANCELLED) {
          log.info("[{}] Job was cancelled during upload, skipping success", strId);
          return;
        }
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
            if (j.getStatus() != TranscodingJob.Status.IN_PROGRESS) {
              // retryStuckJobs() already moved this job to a retry cycle — don't double-retry
              log.info("[{}] Job status is {}, skipping self-retry", strId, j.getStatus());
              return;
            }
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
      try { if (zipFile != null) Files.deleteIfExists(zipFile); } catch (IOException ignored) {}
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
      Instant oneDayAgo = Instant.now().minus(Duration.ofDays(1));
      transcodingJobRepository.findAllById(activeProcesses.keySet()).forEach(job -> {
        if (job.getStatus() == TranscodingJob.Status.CANCELLED
            && job.getFinishedAt() != null
            && job.getFinishedAt().isAfter(oneDayAgo)) {
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
    Instant cutoff = Instant.now().minus(Duration.ofDays(2));
    try (var stream = Files.list(root)) {
      stream.forEach(entry -> {
        try {
          if (Files.getLastModifiedTime(entry).toInstant().isBefore(cutoff)) {
            if (Files.isDirectory(entry)) {
              deleteRecursively(entry);
            } else {
              Files.delete(entry);
            }
            log.info("Cleaned up stale output file: {}", entry.getFileName());
          }
        } catch (Exception e) {
          log.warn("Failed to clean output file {}: {}", entry.getFileName(), e.getMessage());
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
          .GET()
          .uri(URI.create(videoUrl))
          .header("Range", "bytes=0-0")
          .timeout(Duration.ofSeconds(30))
          .build();
      var response = client.send(request, HttpResponse.BodyHandlers.discarding());
      int status = response.statusCode();
      if (status >= 400) {
        throw new VideoNotAccessibleException("Video returned HTTP " + status + ": " + videoUrl);
      }
      response.headers().firstValue("content-range").ifPresent(cr -> {
        int slash = cr.lastIndexOf('/');
        if (slash >= 0) {
          try {
            long totalSize = Long.parseLong(cr.substring(slash + 1));
            if (totalSize > config.maxVideoFileSizeBytes) {
              throw new VideoFileTooLargeException(totalSize, config.maxVideoFileSizeBytes);
            }
          } catch (NumberFormatException ignored) {}
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

  private void awsS3Upload(Long jobId, Path source, String s3Key, boolean recursive) throws Exception {
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
    Process process = pb.start();
    activeProcesses.put(jobId, process);
    int exitCode;
    try {
      exitCode = process.waitFor();
    } finally {
      activeProcesses.remove(jobId);
    }
    if (exitCode != 0) {
      throw new RuntimeException("aws s3 cp failed with exit code " + exitCode);
    }
  }

  private Instant expiresAt(TranscodingJob job) {
    if (job.getStatus() != TranscodingJob.Status.SUCCESS) return null;
    if (job.getS3DeletedAt() != null) return null;
    return job.getFinishedAt()
        .plus(Duration.ofSeconds(config.s3ExpirySeconds))
        .minus(Duration.ofSeconds(config.s3ExpirySafetyBufferSeconds));
  }

  private String downloadUrl(TranscodingJob job) {
    Instant exp = expiresAt(job);
    if (exp == null) return null;
    Instant now = Instant.now();
    if (!now.isBefore(exp)) return null;
    Duration remaining = Duration.between(now, exp);
    Duration presignedUrlMax = Duration.ofSeconds(config.presignedUrlMaxSeconds);
    Duration urlTtl = remaining.compareTo(presignedUrlMax) < 0 ? remaining : presignedUrlMax;
    var presignRequest = GetObjectPresignRequest.builder()
        .signatureDuration(urlTtl)
        .getObjectRequest(GetObjectRequest.builder()
            .bucket(config.s3Bucket)
            .key(job.getStrId() + ".zip")
            .build())
        .build();
    return s3Presigner.presignGetObject(presignRequest).url().toString();
  }

  private void cleanupJobs() {
    try {
      Instant s3ExpiryThreshold = Instant.now().minus(Duration.ofSeconds(config.s3ExpirySeconds));
      for (TranscodingJob job : transcodingJobRepository.findJobsForS3Cleanup(s3ExpiryThreshold)) {
        try {
          deleteS3Object(job.getStrId() + ".zip");
          transactionTemplate.executeWithoutResult(status ->
              transcodingJobRepository.findById(job.getId()).ifPresent(j -> {
                j.markS3Deleted();
                transcodingJobRepository.save(j);
              })
          );
          log.info("[{}] S3 object deleted", job.getStrId());
        } catch (Exception e) {
          log.warn("[{}] Failed to delete S3 object: {}", job.getStrId(), e.getMessage());
        }
      }
    } catch (Exception e) {
      log.warn("S3 cleanup failed: {}", e.getMessage());
    }

    try {
      Instant retentionThreshold = Instant.now().minus(Duration.ofSeconds(config.retentionSeconds));
      List<TranscodingJob> jobsToDelete = transcodingJobRepository.findJobsForDeletion(retentionThreshold);
      if (!jobsToDelete.isEmpty()) {
        transcodingJobRepository.deleteAll(jobsToDelete);
        log.info("Deleted {} expired jobs", jobsToDelete.size());
      }
    } catch (Exception e) {
      log.warn("Job deletion failed: {}", e.getMessage());
    }
  }

  private void deleteS3Object(String key) {
    s3Client.deleteObject(DeleteObjectRequest.builder()
        .bucket(config.s3Bucket)
        .key(key)
        .build());
  }

  private static void zipDirectory(Path source, Path target) throws IOException {
    try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(target))) {
      Files.walkFileTree(source, new SimpleFileVisitor<>() {
        @Override
        public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
          zos.putNextEntry(new ZipEntry(source.relativize(file).toString()));
          Files.copy(file, zos);
          zos.closeEntry();
          return FileVisitResult.CONTINUE;
        }
      });
    }
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
