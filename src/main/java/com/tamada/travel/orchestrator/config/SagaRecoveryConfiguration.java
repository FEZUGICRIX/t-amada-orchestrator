package com.tamada.travel.orchestrator.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(SagaRecoveryProperties.class)
public class SagaRecoveryConfiguration {
}
