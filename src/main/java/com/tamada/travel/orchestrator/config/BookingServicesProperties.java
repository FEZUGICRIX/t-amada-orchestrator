package com.tamada.travel.orchestrator.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;

@ConfigurationProperties(prefix = "services")
public record BookingServicesProperties(
        ServiceProperties flight,
        ServiceProperties hotel,
        ServiceProperties insurance,
        ServiceProperties transfer
) {

    public record ServiceProperties(URI baseUrl) {
    }
}
