package com.tamada.travel.orchestrator.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * Настройки автоматического управления RANGE-партициями таблицы outbox_messages.
 */
@ConfigurationProperties(prefix = "outbox.partition")
@Validated
public record OutboxPartitionProperties(
        @DefaultValue("true") boolean enabled,
        @Min(1) @DefaultValue("7") int precreateDays,
        @Min(1) @DefaultValue("7") int retentionDays,
        @NotNull @DefaultValue("DROP") CleanupStrategy cleanupStrategy,
        @NotBlank @Pattern(regexp = "^[a-zA-Z_][a-zA-Z0-9_]*$") @DefaultValue("outbox_archive") String archiveSchema,
        @NotNull @DefaultValue("PT1H") Duration fixedDelay,
        @NotNull @DefaultValue("PT30S") Duration initialDelay
) {

    public enum CleanupStrategy {
        DROP,
        ARCHIVE
    }

    public OutboxPartitionProperties {
        if (precreateDays < 1) {
            precreateDays = 7;
        }
        if (retentionDays < 1) {
            retentionDays = 7;
        }
        if (cleanupStrategy == null) {
            cleanupStrategy = CleanupStrategy.DROP;
        }
        if (archiveSchema == null || archiveSchema.isBlank()) {
            archiveSchema = "outbox_archive";
        }
        if (fixedDelay == null) {
            fixedDelay = Duration.ofHours(1);
        }
        if (initialDelay == null) {
            initialDelay = Duration.ofSeconds(30);
        }
    }
}
