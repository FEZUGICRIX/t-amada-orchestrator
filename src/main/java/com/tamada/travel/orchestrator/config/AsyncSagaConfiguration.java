package com.tamada.travel.orchestrator.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * Конфигурация пула потоков для асинхронного выполнения саги (Saga TaskExecutor).
 * Обеспечивает неблокирующую обработку составных заказов (202 Accepted) и освобождение HTTP-воркеров.
 */
@Configuration(proxyBeanMethods = false)
@EnableAsync
public class AsyncSagaConfiguration {

    @Bean(name = "sagaTaskExecutor")
    public Executor sagaTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(10);
        executor.setMaxPoolSize(50);
        executor.setQueueCapacity(500);
        executor.setThreadNamePrefix("saga-async-");
        executor.initialize();
        return executor;
    }
}
