package com.tamada.travel.orchestrator.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Конфигурационные параметры исполнения саги.
 * Включает возможность фазово-параллельного выполнения независимых услуг.
 */
@ConfigurationProperties(prefix = "saga.execution")
public record SagaExecutionProperties(
        boolean parallelStepsEnabled
) {
    public SagaExecutionProperties() {
        this(false);
    }
}
