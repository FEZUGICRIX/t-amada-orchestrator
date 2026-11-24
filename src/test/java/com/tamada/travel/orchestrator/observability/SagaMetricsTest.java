package com.tamada.travel.orchestrator.observability;

import com.tamada.travel.orchestrator.model.BookingStatus;
import com.tamada.travel.orchestrator.model.BookingType;
import com.tamada.travel.orchestrator.model.OrderStatus;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class SagaMetricsTest {

    private SimpleMeterRegistry registry;
    private SagaMetrics metrics;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        metrics = new SagaMetrics(registry);
    }

    @Test
    void shouldRecordMetricsCorrectly() {
        metrics.recordOrderDuration(OrderStatus.COMPLETED, Duration.ofSeconds(2));
        assertNotNull(registry.find("orchestrator.saga.order.duration").tag("status", "COMPLETED").timer());
        assertEquals(1, registry.find("orchestrator.saga.order.duration").tag("status", "COMPLETED").timer().count());

        metrics.incrementOrderCounter(OrderStatus.COMPLETED);
        assertEquals(1.0, registry.find("orchestrator.saga.orders.total").tag("status", "COMPLETED").counter().count());

        metrics.incrementStepExecution(BookingType.FLIGHT, BookingStatus.CONFIRMED);
        assertEquals(1.0, registry.find("orchestrator.saga.steps.total").tag("type", "FLIGHT").tag("status", "CONFIRMED").counter().count());

        metrics.incrementStepRetry(BookingType.HOTEL, "book");
        assertEquals(1.0, registry.find("orchestrator.booking.client.retries.total").tag("service", "HOTEL").tag("operation", "book").counter().count());

        metrics.incrementCompensation(BookingType.FLIGHT, true);
        assertEquals(1.0, registry.find("orchestrator.saga.compensations.total").tag("type", "FLIGHT").tag("mode", "PARTIAL").counter().count());

        metrics.incrementOutboxPublished("ORDER_COMPLETED");
        assertEquals(1.0, registry.find("orchestrator.outbox.published.total").tag("eventType", "ORDER_COMPLETED").counter().count());

        metrics.incrementOutboxDlq("ORDER_FAILED");
        assertEquals(1.0, registry.find("orchestrator.outbox.dlq.total").tag("eventType", "ORDER_FAILED").counter().count());
    }
}
