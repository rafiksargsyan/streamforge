package com.rsargsyan.streamforge.main_ctx.adapters.driving.workers;

import com.rabbitmq.client.GetResponse;
import com.rsargsyan.streamforge.main_ctx.Config;
import com.rsargsyan.streamforge.main_ctx.core.app.TranscodingJobService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

@Slf4j
@Component
public class TranscodingJobWorker {

  private final RabbitTemplate rabbitTemplate;
  private final TranscodingJobService transcodingJobService;
  private final Config config;
  private volatile boolean running = true;

  @Autowired
  public TranscodingJobWorker(RabbitTemplate rabbitTemplate,
                              TranscodingJobService transcodingJobService,
                              Config config) {
    this.rabbitTemplate = rabbitTemplate;
    this.transcodingJobService = transcodingJobService;
    this.config = config;
  }

  @PostConstruct
  public void start() {
    Thread.ofVirtual().name("transcoding-job-worker").start(this::pollLoop);
  }

  @PreDestroy
  public void stop() {
    running = false;
  }

  private void pollLoop() {
    long pollIntervalMillis = TimeUnit.SECONDS.toMillis(config.pollIntervalSeconds);
    while (running) {
      try {
        transcodingJobService.acquireSlot();

        Boolean received = rabbitTemplate.execute(channel -> {
          GetResponse response = channel.basicGet(config.queueName, false);
          if (response == null) {
            transcodingJobService.releaseSlot();
            return false;
          }

          String strId = new String(response.getBody());
          long deliveryTag = response.getEnvelope().getDeliveryTag();
          log.info("[{}] Received from RabbitMQ", strId);

          transcodingJobService.submit(strId, () -> {
            try {
              channel.basicAck(deliveryTag, false);
            } catch (Exception e) {
              log.warn("[{}] Failed to ACK message", strId, e);
            }
          });
          return true;
        });

        if (!Boolean.TRUE.equals(received)) {
          Thread.sleep(pollIntervalMillis);
        }
      } catch (Exception e) {
        log.error("Unexpected error in poll loop", e);
      }
    }
  }
}
