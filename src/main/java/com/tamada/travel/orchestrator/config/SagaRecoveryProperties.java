package com.tamada.travel.orchestrator.config;

import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

@ConfigurationProperties(prefix = "saga.recovery")
@Validated
public record SagaRecoveryProperties(
        @DefaultValue("true") boolean enabled,
        @NotNull @DefaultValue("2m") Duration stuckThreshold,
        @NotNull @DefaultValue("60s") Duration fixedDelay,
        @NotNull @DefaultValue("60s") Duration initialDelay
) {
    public SagaRecoveryProperties {
        if (stuckThreshold == null) {
            stuckThreshold = Duration.ofMinutes(2);
        }
        if (fixedDelay == null) {
            fixedDelay = Duration.ofSeconds(60);
        }
        if (initialDelay == null) {
            initialDelay = Duration.ofSeconds(60);
        }
    }
}
