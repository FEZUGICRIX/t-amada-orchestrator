package com.tamada.travel.orchestrator.model;

/**
 * Статус жизненного цикла записи идемпотентности запроса.
 */
public enum IdempotencyStatus {
    IN_PROGRESS,
    COMPLETED,
    FAILED
}
