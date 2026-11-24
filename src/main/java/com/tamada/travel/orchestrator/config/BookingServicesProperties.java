package com.tamada.travel.orchestrator.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.net.URI;
import java.time.Duration;

@ConfigurationProperties(prefix = "services")
@Validated
public record BookingServicesProperties(
        ServiceProperties flight,
        ServiceProperties hotel,
        ServiceProperties insurance,
        ServiceProperties transfer,
        @DefaultValue("5s") Duration connectTimeout,
        @DefaultValue("10s") Duration readTimeout,
        RetryProperties retry,
        CircuitBreakerProperties circuitBreaker
) {

    public BookingServicesProperties {
        if (retry == null) {
            retry = new RetryProperties(3, Duration.ofMillis(200), 2.0, Duration.ofSeconds(2), false);
        }
        if (circuitBreaker == null) {
            circuitBreaker = new CircuitBreakerProperties(true, 50.0f, 10, 4, Duration.ofSeconds(10), 3);
        }
    }

    public record ServiceProperties(URI baseUrl) {
    }

    public record RetryProperties(
            @DefaultValue("3") int maxAttempts,
            @DefaultValue("200ms") Duration initialDelay,
            @DefaultValue("2.0") double multiplier,
            @DefaultValue("2s") Duration maxDelay,
            @DefaultValue("false") boolean jitterEnabled
    ) {
        public RetryProperties {
            if (maxAttempts < 1) {
                maxAttempts = 1;
            }
            if (initialDelay == null) {
                initialDelay = Duration.ofMillis(200);
            }
            if (multiplier < 1.0) {
                multiplier = 1.0;
            }
            if (maxDelay == null) {
                maxDelay = Duration.ofSeconds(2);
            }
        }

        public RetryProperties(int maxAttempts, Duration initialDelay, double multiplier, Duration maxDelay) {
            this(maxAttempts, initialDelay, multiplier, maxDelay, false);
        }
    }

    public record CircuitBreakerProperties(
            @DefaultValue("true") boolean enabled,
            @DefaultValue("50.0") float failureRateThreshold,
            @DefaultValue("10") int slidingWindowSize,
            @DefaultValue("4") int minimumNumberOfCalls,
            @DefaultValue("10s") Duration waitDurationInOpenState,
            @DefaultValue("3") int permittedNumberOfCallsInHalfOpenState
    ) {
        public CircuitBreakerProperties {
            if (failureRateThreshold <= 0 || failureRateThreshold > 100) {
                failureRateThreshold = 50.0f;
            }
            if (slidingWindowSize < 1) {
                slidingWindowSize = 10;
            }
            if (minimumNumberOfCalls < 1) {
                minimumNumberOfCalls = 4;
            }
            if (waitDurationInOpenState == null) {
                waitDurationInOpenState = Duration.ofSeconds(10);
            }
            if (permittedNumberOfCallsInHalfOpenState < 1) {
                permittedNumberOfCallsInHalfOpenState = 3;
            }
        }
    }
}
