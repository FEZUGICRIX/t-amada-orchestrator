package com.tamada.travel.orchestrator.service.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "security.encryption")
public record EncryptionProperties(
        boolean enabled,
        String secretKey
) {
    public EncryptionProperties() {
        this(true, "tamada-secret-encryption-key-256bit-ok!");
    }
}
