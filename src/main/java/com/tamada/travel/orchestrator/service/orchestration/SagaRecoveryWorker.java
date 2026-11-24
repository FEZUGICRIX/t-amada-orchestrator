package com.tamada.travel.orchestrator.service.orchestration;

import com.tamada.travel.orchestrator.config.SagaRecoveryProperties;
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
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Фоновый воркер авто-восстановления зависших заказов (Saga Recovery).
 * Делегирует исполнение единому сервису {@link SagaWatchdogService}.
 */
@Component
@Slf4j
public class SagaRecoveryWorker {

    private final CompositeOrderRepository repository;
    private final CompositeOrderService orderService;
    private final SagaRecoveryProperties properties;
    private final Clock clock;
    private final SagaWatchdogService watchdogService;
    private final AtomicBoolean isRecovering = new AtomicBoolean(false);

    @Autowired
    public SagaRecoveryWorker(
            CompositeOrderRepository repository,
            CompositeOrderService orderService,
            SagaRecoveryProperties properties,
            @Autowired(required = false) Clock clock,
            @Autowired(required = false) SagaWatchdogService watchdogService
    ) {
        this.repository = Objects.requireNonNull(repository, "repository must not be null");
        this.orderService = Objects.requireNonNull(orderService, "orderService must not be null");
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
        this.clock = clock != null ? clock : Clock.systemUTC();
        this.watchdogService = watchdogService != null
                ? watchdogService
                : new SagaWatchdogService(this.repository, this.orderService, this.clock);
    }

    public SagaRecoveryWorker(
            CompositeOrderRepository repository,
            CompositeOrderService orderService,
            SagaRecoveryProperties properties,
            Clock clock
    ) {
        this(repository, orderService, properties, clock, null);
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

            return watchdogService.processOrdersByStatus(
                    OrderStatus.PROCESSING,
                    cutoff,
                    orderService::recoverOrder,
                    "auto-recovery"
            );
        } finally {
            isRecovering.set(false);
        }
    }
}
