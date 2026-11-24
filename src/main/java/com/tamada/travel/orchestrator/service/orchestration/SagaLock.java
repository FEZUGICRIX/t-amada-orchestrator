package com.tamada.travel.orchestrator.service.orchestration;

import java.time.Duration;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Контракт управления блокировками саги (Dependency Inversion Principle).
 * Абстрагирует механизм взаимного исключения (In-Memory, Redis, Postgres Advisory Lock)
 * от логики выполнения саги.
 */
public interface SagaLock {

    <T> T executeWithLock(UUID orderId, Duration timeout, Supplier<T> action);

    default <T> T executeWithLock(UUID orderId, Supplier<T> action) {
        return executeWithLock(orderId, Duration.ZERO, action);
    }

    default void executeWithLock(UUID orderId, Duration timeout, Runnable action) {
        executeWithLock(orderId, timeout, () -> {
            action.run();
            return null;
        });
    }

    default void executeWithLock(UUID orderId, Runnable action) {
        executeWithLock(orderId, Duration.ZERO, action);
    }
}
