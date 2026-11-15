package com.tamada.travel.orchestrator.service.orchestration;

import com.tamada.travel.orchestrator.exception.InvalidStateTransitionException;
import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * Механизм взаимного исключения для предотвращения race conditions при одновременных
 * вызовах операций оркестрации и отмены одного и того же заказа.
 */
@Component
public class SagaExecutionLock {

    private final ConcurrentHashMap<UUID, ReentrantLock> locks = new ConcurrentHashMap<>();

    public <T> T executeWithLock(UUID orderId, Supplier<T> action) {
        ReentrantLock lock = locks.computeIfAbsent(orderId, id -> new ReentrantLock());
        if (!lock.tryLock()) {
            throw new InvalidStateTransitionException(
                    "Order " + orderId + " is currently locked by another orchestration operation"
            );
        }
        try {
            return action.get();
        } finally {
            lock.unlock();
            locks.computeIfPresent(orderId, (id, l) -> l.hasQueuedThreads() ? l : null);
        }
    }

    public void executeWithLock(UUID orderId, Runnable action) {
        executeWithLock(orderId, () -> {
            action.run();
            return null;
        });
    }
}
