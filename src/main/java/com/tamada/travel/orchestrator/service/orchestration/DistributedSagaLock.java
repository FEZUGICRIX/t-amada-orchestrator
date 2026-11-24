package com.tamada.travel.orchestrator.service.orchestration;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Единый координатор блокировок саги с авто-определением окружения.
 * В PostgreSQL окружении использует гибридный двухуровневый замок:
 * локальный {@link SagaExecutionLock} + кластерный {@link PostgresAdvisorySagaLock}.
 * В тестовом окружении (H2) прозрачно переключается на потокобезопасный in-memory замок.
 */
@Component
@Primary
@Slf4j
public class DistributedSagaLock implements SagaLock {

    private final SagaExecutionLock inMemoryLock;
    private final PostgresAdvisorySagaLock postgresAdvisoryLock;
    private final JdbcTemplate jdbcTemplate;
    private final java.util.concurrent.locks.ReentrantLock envCheckLock = new java.util.concurrent.locks.ReentrantLock();
    private volatile Boolean isPostgresDatabase = null;

    @Autowired
    public DistributedSagaLock(
            SagaExecutionLock inMemoryLock,
            PostgresAdvisorySagaLock postgresAdvisoryLock,
            JdbcTemplate jdbcTemplate
    ) {
        this.inMemoryLock = inMemoryLock;
        this.postgresAdvisoryLock = postgresAdvisoryLock;
        this.jdbcTemplate = jdbcTemplate;
    }

    private boolean isPostgres() {
        if (isPostgresDatabase == null) {
            envCheckLock.lock();
            try {
                if (isPostgresDatabase == null) {
                    try {
                        String productName = jdbcTemplate.execute(
                                (ConnectionCallback<String>) conn -> conn.getMetaData().getDatabaseProductName()
                        );
                        isPostgresDatabase = productName != null && productName.toLowerCase().contains("postgres");
                        log.info("Detected database product: {} (Postgres lock active: {})", productName, isPostgresDatabase);
                    } catch (Exception ex) {
                        log.warn("Could not determine database product name, defaulting to in-memory lock: {}", ex.getMessage());
                        isPostgresDatabase = false;
                    }
                }
            } finally {
                envCheckLock.unlock();
            }
        }
        return isPostgresDatabase;
    }

    @Override
    public <T> T executeWithLock(UUID orderId, Duration timeout, Supplier<T> action) {
        if (isPostgres()) {
            // Двухуровневая блокировка: сначала локальный замок в JVM, затем кластерный замок в PostgreSQL
            return inMemoryLock.executeWithLock(orderId, timeout, () ->
                    postgresAdvisoryLock.executeWithLock(orderId, timeout, action)
            );
        }
        // Fallback для H2 и юнит-тестов
        return inMemoryLock.executeWithLock(orderId, timeout, action);
    }
}
