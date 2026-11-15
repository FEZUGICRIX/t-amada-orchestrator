package com.tamada.travel.orchestrator.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.net.URI;
import java.time.Duration;

@ConfigurationProperties(prefix = "services")
public record BookingServicesProperties(
        ServiceProperties flight,
        ServiceProperties hotel,
        ServiceProperties insurance,
        ServiceProperties transfer,
        @DefaultValue("5s") Duration connectTimeout,
        @DefaultValue("10s") Duration readTimeout,
        RetryProperties retry
) {

    public BookingServicesProperties {
        if (retry == null) {
            retry = new RetryProperties(3, Duration.ofMillis(200), 2.0, Duration.ofSeconds(2));
        }
    }

    public record ServiceProperties(URI baseUrl) {
    }

    public record RetryProperties(
            @DefaultValue("3") int maxAttempts,
            @DefaultValue("200ms") Duration initialDelay,
            @DefaultValue("2.0") double multiplier,
            @DefaultValue("2s") Duration maxDelay
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
    }
}
