package com.rsargsyan.streamforge.main_ctx;

import com.rsargsyan.streamforge.main_ctx.core.ports.repository.TranscodingJobRepository;
import io.hypersistence.tsid.TSID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import java.net.URI;
import java.time.Instant;

@Slf4j
@Configuration
public class Config {

  @Autowired
  private TranscodingJobRepository transcodingJobRepository;

  @Value("${rabbitmq.queue}")
  public String queueName;

  @Value("${rabbitmq.topic.exchange.name}")
  public String topicExchangeName;

  @Value("${rabbitmq.routing-key}")
  public String routingKey;

  @Value("${s3.access-key-id}")
  public String s3AccessKeyId;

  @Value("${s3.secret-access-key}")
  public String s3SecretAccessKey;

  @Value("${s3.region}")
  public String s3Region;

  @Value("${s3.endpoint}")
  public String s3Endpoint;

  @Value("${s3.bucket}")
  public String s3Bucket;

  @Value("${job.max-video-file-size-bytes}")
  public long maxVideoFileSizeBytes;

  @Value("${ffmpeg.threads:2}")
  public int ffmpegThreads;

  @Value("${job.processing-pool-size:3}")
  public int processingPoolSize;

  @Value("${job.heartbeat-interval-seconds:30}")
  public int heartbeatIntervalSeconds;

  @Value("${job.stale-heartbeat-seconds:120}")
  public int staleHeartbeatSeconds;

  @Value("${job.max-retries:3}")
  public int maxRetries;

  @Value("${job.base-output-folder}")
  public String baseOutputFolder;

  @Value("${job.output-url-ttl-hours:24}")
  public int outputUrlTtlHours;

  @Value("${job.poll-interval-seconds:5}")
  public int pollIntervalSeconds;

  @Profile("worker")
  @Bean
  public S3Client s3Client() {
    return S3Client.builder()
        .endpointOverride(URI.create(s3Endpoint))
        .region(Region.of(s3Region))
        .credentialsProvider(StaticCredentialsProvider.create(
            AwsBasicCredentials.create(s3AccessKeyId, s3SecretAccessKey)))
        .build();
  }

  @Bean
  public RabbitTemplate rabbitTemplate(CachingConnectionFactory connectionFactory) {
    connectionFactory.setPublisherConfirmType(CachingConnectionFactory.ConfirmType.CORRELATED);
    RabbitTemplate template = new RabbitTemplate(connectionFactory);
    template.setConfirmCallback((correlationData, ack, cause) -> {
      if (correlationData == null) return;
      if (ack) {
        transcodingJobRepository.updateMqConfirmedAt(
            TSID.from(correlationData.getId()).toLong(), Instant.now());
      } else {
        log.warn("RabbitMQ NACKed message for job {}: {}", correlationData.getId(), cause);
      }
    });
    return template;
  }

  @Profile("web")
  @Bean(destroyMethod = "close")
  public S3Presigner s3Presigner() {
    return S3Presigner.builder()
        .endpointOverride(URI.create(s3Endpoint))
        .region(Region.of(s3Region))
        .credentialsProvider(StaticCredentialsProvider.create(
            AwsBasicCredentials.create(s3AccessKeyId, s3SecretAccessKey)))
        .build();
  }
}
