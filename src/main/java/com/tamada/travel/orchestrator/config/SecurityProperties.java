package com.tamada.travel.orchestrator.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "security.api-key")
public record SecurityProperties(
        boolean enabled,
        String headerName,
        String apiKey
) {
    public SecurityProperties() {
        this(false, "X-API-KEY", "tamada-orchestrator-api-key-production");
    }
}
