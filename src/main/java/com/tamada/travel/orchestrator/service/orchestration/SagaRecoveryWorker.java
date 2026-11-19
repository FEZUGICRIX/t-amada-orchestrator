package com.tamada.travel.orchestrator.service.orchestration;

import com.tamada.travel.orchestrator.config.SagaRecoveryProperties;
import com.tamada.travel.orchestrator.model.CompositeOrder;
import com.tamada.travel.orchestrator.model.OrderStatus;
import com.tamada.travel.orchestrator.repository.CompositeOrderRepository;
import com.tamada.travel.orchestrator.service.CompositeOrderService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Фоновый воркер авто-восстановления зависших заказов (Saga Recovery).
 * <p>
 * При старте приложения (ApplicationReadyEvent) и периодически по таймеру (@Scheduled):
 * 1. Находит в базе заказы со статусом PROCESSING, висящие дольше заданного порога (по умолчанию 2 минуты).
 * 2. Безопасно возобновляет сагу: пропускает уже подтвержденные услуги (CONFIRMED) и отправляет запросы
 *    только на оставшиеся (PENDING).
 * 3. Доводит заказ до COMPLETED (в случае успеха всех шагов) или CANCELLED (при сбое и компенсации).
 */
@Component
@Slf4j
public class SagaRecoveryWorker {

    private final CompositeOrderRepository repository;
    private final CompositeOrderService orderService;
    private final SagaRecoveryProperties properties;
    private final Clock clock;
    private final AtomicBoolean isRecovering = new AtomicBoolean(false);

    @Autowired
    public SagaRecoveryWorker(
            CompositeOrderRepository repository,
            CompositeOrderService orderService,
            SagaRecoveryProperties properties,
            @Autowired(required = false) Clock clock
    ) {
        this.repository = Objects.requireNonNull(repository, "repository must not be null");
        this.orderService = Objects.requireNonNull(orderService, "orderService must not be null");
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
        this.clock = clock != null ? clock : Clock.systemUTC();
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        if (!properties.enabled()) {
            log.info("Saga recovery worker is disabled, skipping startup recovery");
            return;
        }
        log.info("Triggering saga recovery on application startup");
        recoverStuckOrders();
    }

    @Scheduled(
            fixedDelayString = "${saga.recovery.fixed-delay:60s}",
            initialDelayString = "${saga.recovery.initial-delay:60s}"
    )
    public void scheduledRecovery() {
        if (!properties.enabled()) {
            return;
        }
        recoverStuckOrders();
    }

    public int recoverStuckOrders() {
        if (!isRecovering.compareAndSet(false, true)) {
            log.warn("Saga recovery is already in progress, skipping trigger");
            return 0;
        }

        try {
            Instant now = Instant.now(clock);
            Instant cutoff = now.minus(properties.stuckThreshold());
            log.debug("Scanning for stuck PROCESSING orders modified before {}", cutoff);

            List<CompositeOrder> stuckOrders = repository.findByStatusAndUpdatedAtBeforeOrderByUpdatedAtAsc(
                    OrderStatus.PROCESSING,
                    cutoff
            );

            if (stuckOrders.isEmpty()) {
                log.debug("No stuck orders found for recovery");
                return 0;
            }

            log.info("Found {} stuck order(s) for recovery", stuckOrders.size());
            int recoveredCount = 0;

            for (CompositeOrder order : stuckOrders) {
                try {
                    log.info("Attempting auto-recovery for order id={}", order.getId());
                    orderService.recoverOrder(order.getId());
                    recoveredCount++;
                    log.info("Successfully recovered order id={}", order.getId());
                } catch (Exception ex) {
                    log.error("Failed to recover stuck order id={}: {}", order.getId(), ex.getMessage(), ex);
                }
            }

            return recoveredCount;
        } finally {
            isRecovering.set(false);
        }
    }
}
