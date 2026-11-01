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
        @DefaultValue("10s") Duration readTimeout
) {

    public record ServiceProperties(URI baseUrl) {
    }
}
