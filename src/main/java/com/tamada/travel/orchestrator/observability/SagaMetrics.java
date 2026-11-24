package com.tamada.travel.orchestrator.observability;

import com.tamada.travel.orchestrator.model.BookingStatus;
import com.tamada.travel.orchestrator.model.BookingType;
import com.tamada.travel.orchestrator.model.OrderStatus;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Менеджер метрик бизнес-логики саги и оркестратора (Micrometer / Prometheus).
 * Предоставляет гранулярные метрики для построения дашбордов Grafana:
 * - Длительность выполнения заказов (SLA)
 * - Счетчики успешных, частично компенсированных и отмененных саг
 * - Распределение ошибок по типам сервисов бронирования
 * - Метрики повторных попыток (Retry) и очередей Dead Letter Queue
 */
@Component
public class SagaMetrics {

    private final MeterRegistry registry;

    @org.springframework.beans.factory.annotation.Autowired
    public SagaMetrics(ObjectProvider<MeterRegistry> registryProvider) {
        MeterRegistry reg = registryProvider != null ? registryProvider.getIfAvailable() : null;
        this.registry = reg != null ? reg : new SimpleMeterRegistry();
    }

    public SagaMetrics() {
        this(new SimpleMeterRegistry());
    }

    public SagaMetrics(MeterRegistry registry) {
        this.registry = registry != null ? registry : new SimpleMeterRegistry();
    }

    public void recordOrderDuration(OrderStatus status, Duration duration) {
        if (duration != null && !duration.isNegative()) {
            Timer.builder("orchestrator.saga.order.duration")
                    .description("Duration of saga execution from creation/start to terminal state")
                    .tag("status", status != null ? status.name() : "UNKNOWN")
                    .register(registry)
                    .record(duration);
        }
    }

    public void incrementOrderCounter(OrderStatus status) {
        Counter.builder("orchestrator.saga.orders.total")
                .description("Total number of orders reaching terminal state")
                .tag("status", status != null ? status.name() : "UNKNOWN")
                .register(registry)
                .increment();
    }

    public void incrementStepExecution(BookingType type, BookingStatus status) {
        Counter.builder("orchestrator.saga.steps.total")
                .description("Total number of booking steps executed")
                .tag("type", type != null ? type.name() : "UNKNOWN")
                .tag("status", status != null ? status.name() : "UNKNOWN")
                .register(registry)
                .increment();
    }

    public void incrementStepRetry(BookingType type, String operation) {
        Counter.builder("orchestrator.booking.client.retries.total")
                .description("Total number of retry attempts made against external booking services")
                .tag("service", type != null ? type.name() : "UNKNOWN")
                .tag("operation", operation != null ? operation : "UNKNOWN")
                .register(registry)
                .increment();
    }

    public void incrementCompensation(BookingType type, boolean partial) {
        Counter.builder("orchestrator.saga.compensations.total")
                .description("Total number of compensations performed")
                .tag("type", type != null ? type.name() : "UNKNOWN")
                .tag("mode", partial ? "PARTIAL" : "FULL")
                .register(registry)
                .increment();
    }

    public void incrementOutboxPublished(String eventType) {
        Counter.builder("orchestrator.outbox.published.total")
                .description("Total number of events published to Kafka")
                .tag("eventType", eventType != null ? eventType : "UNKNOWN")
                .register(registry)
                .increment();
    }

    public void incrementOutboxDlq(String eventType) {
        Counter.builder("orchestrator.outbox.dlq.total")
                .description("Total number of events routed to Dead Letter Queue")
                .tag("eventType", eventType != null ? eventType : "UNKNOWN")
                .register(registry)
                .increment();
    }

    public MeterRegistry getRegistry() {
        return registry;
    }
}
