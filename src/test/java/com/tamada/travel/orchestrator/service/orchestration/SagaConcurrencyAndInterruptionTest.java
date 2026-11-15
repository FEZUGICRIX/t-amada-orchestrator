package com.tamada.travel.orchestrator.service.orchestration;

import com.tamada.travel.orchestrator.client.BookingClient;
import com.tamada.travel.orchestrator.client.BookingClientRegistry;
import com.tamada.travel.orchestrator.client.dto.BookingRequest;
import com.tamada.travel.orchestrator.client.dto.BookingResponse;
import com.tamada.travel.orchestrator.client.dto.CancelBookingRequest;
import com.tamada.travel.orchestrator.client.dto.CancelBookingResponse;
import com.tamada.travel.orchestrator.exception.InvalidStateTransitionException;
import com.tamada.travel.orchestrator.model.BookingStatus;
import com.tamada.travel.orchestrator.model.BookingStep;
import com.tamada.travel.orchestrator.model.BookingType;
import com.tamada.travel.orchestrator.model.CompositeOrder;
import com.tamada.travel.orchestrator.repository.CompositeOrderRepository;
import com.tamada.travel.orchestrator.service.BookingOrchestrationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SagaConcurrencyAndInterruptionTest {

    private final UUID orderId = UUID.randomUUID();
    private CompositeOrder storedOrder;
    private BookingOrchestrationService service;
    private SlowMockBookingClient mockFlightClient;

    @BeforeEach
    void setUp() {
        mockFlightClient = new SlowMockBookingClient(BookingType.FLIGHT);
        BookingClientRegistry registry = new BookingClientRegistry(List.of(mockFlightClient));
        TransactionTemplate transactionTemplate = new TransactionTemplate(new NoOpTransactionManager());

        service = new BookingOrchestrationService(
                createRepository(),
                registry,
                transactionTemplate
        );
    }

    @Test
    void shouldStopOrchestrationWhenThreadIsInterrupted() {
        CompositeOrder order = createOrder(BookingType.FLIGHT);
        Thread.currentThread().interrupt();

        try {
            IllegalStateException ex = assertThrows(
                    IllegalStateException.class,
                    () -> service.orchestrate(orderId)
            );
            assertTrue(ex.getMessage().contains("interrupted"));
        } finally {
            Thread.interrupted(); // Очищаем флаг прерывания
        }
    }

    @Test
    void shouldRejectConcurrentStartOnSameOrder() throws Exception {
        CompositeOrder order = createOrder(BookingType.FLIGHT);
        mockFlightClient.setDelayMillis(500);

        CountDownLatch startCalled = new CountDownLatch(1);
        AtomicBoolean secondStartRejected = new AtomicBoolean(false);

        Thread thread1 = new Thread(() -> {
            startCalled.countDown();
            service.orchestrate(orderId);
        });

        thread1.start();
        assertTrue(startCalled.await(1, TimeUnit.SECONDS));
        Thread.sleep(50); // Даем первому потоку зайти в executeWithLock

        Thread thread2 = new Thread(() -> {
            try {
                service.orchestrate(orderId);
            } catch (InvalidStateTransitionException ex) {
                secondStartRejected.set(true);
            }
        });

        thread2.start();
        thread2.join(2000);
        thread1.join(2000);

        assertTrue(secondStartRejected.get(), "Второй одновременный вызов start должен получить 409 (InvalidStateTransitionException)");
    }

    @Test
    void shouldRejectCancelWhileStartIsRunningOnSameOrder() throws Exception {
        CompositeOrder order = createOrder(BookingType.FLIGHT);
        mockFlightClient.setDelayMillis(500);

        CountDownLatch startCalled = new CountDownLatch(1);
        AtomicBoolean cancelRejected = new AtomicBoolean(false);

        Thread thread1 = new Thread(() -> {
            startCalled.countDown();
            service.orchestrate(orderId);
        });

        thread1.start();
        assertTrue(startCalled.await(1, TimeUnit.SECONDS));
        Thread.sleep(50);

        Thread thread2 = new Thread(() -> {
            try {
                service.cancel(orderId);
            } catch (InvalidStateTransitionException ex) {
                cancelRejected.set(true);
            }
        });

        thread2.start();
        thread2.join(2000);
        thread1.join(2000);

        assertTrue(cancelRejected.get(), "Вызов cancel во время выполнения start должен быть отклонен");
    }

    private CompositeOrder createOrder(BookingType... types) {
        CompositeOrder order = CompositeOrder.create();
        ReflectionTestUtils.setField(order, "id", orderId);
        for (BookingType type : types) {
            order.addBookingStep(type, UUID.randomUUID());
            ReflectionTestUtils.setField(
                    order.getBookingSteps().getLast(),
                    "id",
                    UUID.randomUUID()
            );
        }
        storedOrder = order;
        return order;
    }

    private CompositeOrderRepository createRepository() {
        return (CompositeOrderRepository) Proxy.newProxyInstance(
                CompositeOrderRepository.class.getClassLoader(),
                new Class<?>[]{CompositeOrderRepository.class},
                (proxy, method, arguments) -> {
                    if (method.getName().equals("findById")) {
                        return Optional.ofNullable(storedOrder);
                    }
                    throw new UnsupportedOperationException(method.getName());
                }
        );
    }

    private static class SlowMockBookingClient implements BookingClient {
        private final BookingType type;
        private long delayMillis = 0;

        private SlowMockBookingClient(BookingType type) {
            this.type = type;
        }

        public void setDelayMillis(long delayMillis) {
            this.delayMillis = delayMillis;
        }

        @Override
        public BookingType type() {
            return type;
        }

        @Override
        public BookingResponse book(BookingRequest request) {
            if (delayMillis > 0) {
                try {
                    Thread.sleep(delayMillis);
                } catch (InterruptedException ignored) {
                }
            }
            return new BookingResponse(
                    UUID.randomUUID(),
                    request.bookingReference(),
                    "CONFIRMED",
                    Instant.now()
            );
        }

        @Override
        public CancelBookingResponse cancel(CancelBookingRequest request) {
            return new CancelBookingResponse(
                    request.bookingId(),
                    "CANCELLED",
                    Instant.now()
            );
        }
    }

    private static class NoOpTransactionManager implements PlatformTransactionManager {
        @Override
        public TransactionStatus getTransaction(TransactionDefinition definition) {
            return new SimpleTransactionStatus();
        }

        @Override
        public void commit(TransactionStatus status) {
        }

        @Override
        public void rollback(TransactionStatus status) {
        }
    }
}
