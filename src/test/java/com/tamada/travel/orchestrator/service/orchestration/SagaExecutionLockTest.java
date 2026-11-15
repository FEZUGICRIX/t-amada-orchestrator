package com.tamada.travel.orchestrator.service.orchestration;

import com.tamada.travel.orchestrator.exception.InvalidStateTransitionException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SagaExecutionLockTest {

    private SagaExecutionLock lock;

    @BeforeEach
    void setUp() {
        lock = new SagaExecutionLock();
    }

    @Test
    void shouldExecuteActionWhenOrderNotLocked() {
        UUID orderId = UUID.randomUUID();
        AtomicBoolean executed = new AtomicBoolean(false);

        lock.executeWithLock(orderId, () -> executed.set(true));

        assertTrue(executed.get());
    }

    @Test
    void shouldRejectConcurrentExecutionOnSameOrder() throws Exception {
        UUID orderId = UUID.randomUUID();
        CountDownLatch lockAcquired = new CountDownLatch(1);
        CountDownLatch releaseLock = new CountDownLatch(1);
        AtomicBoolean secondAttemptFailed = new AtomicBoolean(false);

        Thread thread1 = new Thread(() -> {
            lock.executeWithLock(orderId, () -> {
                lockAcquired.countDown();
                try {
                    releaseLock.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException ignored) {
                }
            });
        });

        thread1.start();
        assertTrue(lockAcquired.await(2, TimeUnit.SECONDS));

        // В это же время второй поток пытается заблокировать тот же заказ
        Thread thread2 = new Thread(() -> {
            try {
                lock.executeWithLock(orderId, () -> {});
            } catch (InvalidStateTransitionException ex) {
                secondAttemptFailed.set(true);
            }
        });

        thread2.start();
        thread2.join(2000);

        releaseLock.countDown();
        thread1.join(2000);

        assertTrue(secondAttemptFailed.get(), "Второй вызов должен быть отклонен с InvalidStateTransitionException");
    }

    @Test
    void shouldAllowConcurrentExecutionsOnDifferentOrders() throws Exception {
        UUID order1 = UUID.randomUUID();
        UUID order2 = UUID.randomUUID();

        CountDownLatch latch1 = new CountDownLatch(1);
        CountDownLatch latch2 = new CountDownLatch(1);
        AtomicBoolean order2Executed = new AtomicBoolean(false);

        Thread thread1 = new Thread(() -> {
            lock.executeWithLock(order1, () -> {
                latch1.countDown();
                try {
                    latch2.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException ignored) {
                }
            });
        });

        thread1.start();
        assertTrue(latch1.await(2, TimeUnit.SECONDS));

        // Второй заказ не должен блокироваться первым
        lock.executeWithLock(order2, () -> order2Executed.set(true));
        assertTrue(order2Executed.get());

        latch2.countDown();
        thread1.join(2000);
    }
}
