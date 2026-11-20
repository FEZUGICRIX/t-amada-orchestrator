package com.tamada.travel.orchestrator.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

@ConfigurationProperties(prefix = "saga.compensation")
public record SagaCompensationProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("2m") Duration stuckThreshold,
        @DefaultValue("60s") Duration fixedDelay,
        @DefaultValue("60s") Duration initialDelay
) {
    public SagaCompensationProperties {
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
