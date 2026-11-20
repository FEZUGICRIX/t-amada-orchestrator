package com.tamada.travel.orchestrator.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

@ConfigurationProperties(prefix = "outbox.publisher")
public record OutboxKafkaProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("tamada.order-events") String topic,
        @DefaultValue("50") int batchSize,
        @DefaultValue("1s") Duration fixedDelay,
        @DefaultValue("1s") Duration initialDelay,
        @DefaultValue("5s") Duration sendTimeout
) {
    public OutboxKafkaProperties {
        if (topic == null || topic.isBlank()) {
            topic = "tamada.order-events";
        }
        if (batchSize < 1) {
            batchSize = 50;
        }
        if (fixedDelay == null) {
            fixedDelay = Duration.ofSeconds(1);
        }
        if (initialDelay == null) {
            initialDelay = Duration.ofSeconds(1);
        }
        if (sendTimeout == null) {
            sendTimeout = Duration.ofSeconds(5);
        }
    }
}
