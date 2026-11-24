package com.tamada.travel.orchestrator.config;

import com.tamada.travel.orchestrator.observability.MdcTaskDecorator;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.support.TaskExecutorAdapter;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Конфигурация исполнителя для асинхронного выполнения саги (Saga TaskExecutor).
 * Использует Virtual Threads (Java 21 Project Loom) для неограниченной масштабируемости I/O,
 * исключает блокировку сервлетных потоков и обеспечивает проброс MDC контекста.
 */
@Configuration(proxyBeanMethods = false)
@EnableAsync
@EnableScheduling
@EnableConfigurationProperties(SagaExecutionProperties.class)
public class AsyncSagaConfiguration {

    @Bean(name = "sagaTaskExecutor", destroyMethod = "destroy")
    public Executor sagaTaskExecutor() {
        var threadFactory = Thread.ofVirtual().name("saga-vt-", 0).factory();
        var executorService = Executors.newThreadPerTaskExecutor(threadFactory);

        class VirtualThreadSagaExecutor extends TaskExecutorAdapter implements org.springframework.beans.factory.DisposableBean {
            public VirtualThreadSagaExecutor() {
                super(executorService);
            }

            @Override
            public void destroy() {
                executorService.shutdown();
                try {
                    if (!executorService.awaitTermination(30, TimeUnit.SECONDS)) {
                        executorService.shutdownNow();
                    }
                } catch (InterruptedException e) {
                    executorService.shutdownNow();
                    Thread.currentThread().interrupt();
                }
            }
        }

        var adapter = new VirtualThreadSagaExecutor();
        adapter.setTaskDecorator(new MdcTaskDecorator());
        return adapter;
    }
}
