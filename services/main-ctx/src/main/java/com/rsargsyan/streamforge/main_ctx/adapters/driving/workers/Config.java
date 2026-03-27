package com.rsargsyan.streamforge.main_ctx.adapters.driving.workers;

import org.springframework.amqp.core.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration("workersConfig")
public class Config {

  @Autowired
  private com.rsargsyan.streamforge.main_ctx.Config config;

  @Bean
  Queue queue() {
    return new Queue(config.queueName, true);
  }

  @Bean
  TopicExchange exchange() {
    return new TopicExchange(config.topicExchangeName);
  }

  @Bean
  Binding binding(Queue queue, TopicExchange exchange) {
    return BindingBuilder.bind(queue).to(exchange).with(config.routingKey);
  }
}
