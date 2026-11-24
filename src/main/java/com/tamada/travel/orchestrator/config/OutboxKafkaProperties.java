package com.tamada.travel.orchestrator.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

@ConfigurationProperties(prefix = "outbox.publisher")
@Validated
public record OutboxKafkaProperties(
        @DefaultValue("true") boolean enabled,
        @NotBlank @DefaultValue("tamada.order-events") String topic,
        @DefaultValue("true") boolean dlqEnabled,
        @NotBlank @DefaultValue("tamada.order-events.DLQ") String dlqTopic,
        @Min(1) @DefaultValue("50") int batchSize,
        @NotNull @DefaultValue("2s") Duration fixedDelay,
        @NotNull @DefaultValue("1s") Duration initialDelay,
        @NotNull @DefaultValue("5s") Duration sendTimeout
) {
    public OutboxKafkaProperties {
        if (topic == null || topic.isBlank()) {
            topic = "tamada.order-events";
        }
        if (dlqTopic == null || dlqTopic.isBlank()) {
            dlqTopic = topic + ".DLQ";
        }
        if (batchSize < 1) {
            batchSize = 50;
        }
        if (fixedDelay == null) {
            fixedDelay = Duration.ofSeconds(2);
        }
        if (initialDelay == null) {
            initialDelay = Duration.ofSeconds(1);
        }
        if (sendTimeout == null) {
            sendTimeout = Duration.ofSeconds(5);
        }
    }
}
