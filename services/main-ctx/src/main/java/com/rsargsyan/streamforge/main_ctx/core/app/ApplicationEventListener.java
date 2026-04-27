package com.rsargsyan.streamforge.main_ctx.core.app;

import com.rsargsyan.streamforge.main_ctx.Config;
import com.rsargsyan.streamforge.main_ctx.core.domain.aggregate.TranscodingJob;
import com.rsargsyan.streamforge.main_ctx.core.ports.repository.TranscodingJobRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Slf4j
@Profile("web")
@Component
public class ApplicationEventListener {

  private final TranscodingJobRepository transcodingJobRepository;
  private final RabbitTemplate rabbitTemplate;
  private final Config config;

  @Autowired
  public ApplicationEventListener(TranscodingJobRepository transcodingJobRepository,
                                  RabbitTemplate rabbitTemplate,
                                  Config config) {
    this.transcodingJobRepository = transcodingJobRepository;
    this.rabbitTemplate = rabbitTemplate;
    this.config = config;
  }

  @EventListener
  public void handleTranscodingJobCreatedEvent(TranscodingJobCreatedEvent event) {
    transcodingJobRepository.findById(event.jobId()).ifPresentOrElse(
        job -> {
          if (job.getStatus() == TranscodingJob.Status.SUBMITTED) {
            job.queue();
            job.markMqSent();
            transcodingJobRepository.save(job);
            sendToRabbitMq(job.getStrId());
          }
        },
        () -> log.warn("Job not found for create event: {}", event.jobId())
    );
  }

  @EventListener
  public void handleTranscodingJobRetryEvent(TranscodingJobRetryEvent event) {
    transcodingJobRepository.findById(event.jobId()).ifPresentOrElse(
        job -> {
          if (job.getStatus() == TranscodingJob.Status.RETRYING) {
            job.queue();
            job.markMqSent();
            transcodingJobRepository.save(job);
            sendToRabbitMq(job.getStrId());
          }
        },
        () -> log.warn("Job not found for retry event: {}", event.jobId())
    );
  }

  private void sendToRabbitMq(String strId) {
    rabbitTemplate.convertAndSend(config.topicExchangeName, config.routingKey, strId, m -> {
      m.getMessageProperties().setDeliveryMode(MessageDeliveryMode.PERSISTENT);
      return m;
    }, new CorrelationData(strId));
  }

}
