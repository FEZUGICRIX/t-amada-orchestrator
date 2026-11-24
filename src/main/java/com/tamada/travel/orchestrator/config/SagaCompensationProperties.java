package com.tamada.travel.orchestrator.config;

import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

@ConfigurationProperties(prefix = "saga.compensation")
@Validated
public record SagaCompensationProperties(
        @DefaultValue("true") boolean enabled,
        @NotNull @DefaultValue("2m") Duration stuckThreshold,
        @NotNull @DefaultValue("60s") Duration fixedDelay,
        @NotNull @DefaultValue("60s") Duration initialDelay,
        @DefaultValue("true") boolean partialCompensationEnabled,
        @DefaultValue("false") boolean continueOnFailure
) {
    @ConstructorBinding
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

    public SagaCompensationProperties(
            boolean enabled,
            Duration stuckThreshold,
            Duration fixedDelay,
            Duration initialDelay
    ) {
        this(enabled, stuckThreshold, fixedDelay, initialDelay, true, false);
    }

    public SagaCompensationProperties(
            boolean enabled,
            Duration stuckThreshold,
            Duration fixedDelay,
            Duration initialDelay,
            boolean partialCompensationEnabled
    ) {
        this(enabled, stuckThreshold, fixedDelay, initialDelay, partialCompensationEnabled, false);
    }
}
