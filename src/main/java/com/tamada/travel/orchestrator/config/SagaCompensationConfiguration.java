package com.tamada.travel.orchestrator.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(SagaCompensationProperties.class)
public class SagaCompensationConfiguration {
}
