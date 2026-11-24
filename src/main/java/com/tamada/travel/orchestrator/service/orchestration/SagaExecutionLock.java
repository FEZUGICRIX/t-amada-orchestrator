package com.tamada.travel.orchestrator.service.orchestration;

import com.tamada.travel.orchestrator.exception.InvalidStateTransitionException;
import com.tamada.travel.orchestrator.observability.CorrelationIdContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * Потокобезопасный механизм взаимного исключения для предотвращения race conditions при одновременных
 * вызовах операций оркестрации, до-оркестрации и отмены одного и того же заказа внутри JVM.
 * Использует атомарный подсчет ссылок для безопасного удаления неиспользуемых блокировок.
 */
@Component
@Slf4j
public class SagaExecutionLock implements SagaLock {

    private static class RefCountedLock {
        final ReentrantLock lock = new ReentrantLock();
        final AtomicInteger refCount = new AtomicInteger(1);
    }

    private final ConcurrentHashMap<UUID, RefCountedLock> locks = new ConcurrentHashMap<>();

    @Override
    public <T> T executeWithLock(UUID orderId, Supplier<T> action) {
        return executeWithLock(orderId, Duration.ZERO, action);
    }

    @Override
    public <T> T executeWithLock(UUID orderId, Duration timeout, Supplier<T> action) {
        RefCountedLock refLock = locks.compute(orderId, (id, existing) -> {
            if (existing == null) {
                return new RefCountedLock();
            }
            existing.refCount.incrementAndGet();
            return existing;
        });

        boolean acquired;
        try {
            if (timeout == null || timeout.isZero() || timeout.isNegative()) {
                acquired = refLock.lock.tryLock();
            } else {
                acquired = refLock.lock.tryLock(timeout.toMillis(), TimeUnit.MILLISECONDS);
            }
        } catch (InterruptedException e) {
            releaseRef(orderId, refLock);
            Thread.currentThread().interrupt();
            throw new InvalidStateTransitionException(
                    "Interrupted while waiting for lock on order " + orderId
            );
        }

        if (!acquired) {
            releaseRef(orderId, refLock);
            log.warn("Order {} is currently locked by another orchestration operation", orderId);
            throw new InvalidStateTransitionException(
                    "Order " + orderId + " is currently locked by another orchestration operation"
            );
        }

        CorrelationIdContext.setOrderId(orderId);
        try {
            return action.get();
        } finally {
            CorrelationIdContext.clearOrderId();
            refLock.lock.unlock();
            releaseRef(orderId, refLock);
        }
    }

    private void releaseRef(UUID orderId, RefCountedLock refLock) {
        locks.computeIfPresent(orderId, (id, existing) -> {
            if (existing == refLock && existing.refCount.decrementAndGet() <= 0) {
                return null;
            }
            return existing;
        });
    }

    public void executeWithLock(UUID orderId, Runnable action) {
        executeWithLock(orderId, () -> {
            action.run();
            return null;
        });
    }

    public void executeWithLock(UUID orderId, Duration timeout, Runnable action) {
        executeWithLock(orderId, timeout, () -> {
            action.run();
            return null;
        });
    }
}
