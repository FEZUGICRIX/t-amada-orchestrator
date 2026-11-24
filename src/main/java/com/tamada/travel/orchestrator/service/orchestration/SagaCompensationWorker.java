package com.tamada.travel.orchestrator.service.orchestration;

import com.tamada.travel.orchestrator.config.SagaCompensationProperties;
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
 * Фоновый воркер-планировщик для гарантированной компенсации зависших отмен (Compensation Scheduler).
 * Делегирует исполнение единому сервису {@link SagaWatchdogService}.
 */
@Component
@Slf4j
public class SagaCompensationWorker {

    private final CompositeOrderRepository repository;
    private final CompositeOrderService orderService;
    private final SagaCompensationProperties properties;
    private final Clock clock;
    private final SagaWatchdogService watchdogService;
    private final AtomicBoolean isCompensating = new AtomicBoolean(false);

    @Autowired
    public SagaCompensationWorker(
            CompositeOrderRepository repository,
            CompositeOrderService orderService,
            SagaCompensationProperties properties,
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

    public SagaCompensationWorker(
            CompositeOrderRepository repository,
            CompositeOrderService orderService,
            SagaCompensationProperties properties,
            Clock clock
    ) {
        this(repository, orderService, properties, clock, null);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        if (!properties.enabled()) {
            log.info("Saga compensation scheduler is disabled, skipping startup check");
            return;
        }
        log.info("Triggering saga compensation scheduler on application startup");
        compensateStuckOrders();
    }

    @Scheduled(
            fixedDelayString = "${saga.compensation.fixed-delay:60s}",
            initialDelayString = "${saga.compensation.initial-delay:60s}"
    )
    public void scheduledCompensation() {
        if (!properties.enabled()) {
            return;
        }
        compensateStuckOrders();
    }

    public int compensateStuckOrders() {
        if (!isCompensating.compareAndSet(false, true)) {
            log.warn("Saga compensation scheduler is already in progress, skipping trigger");
            return 0;
        }

        try {
            Instant now = Instant.now(clock);
            Instant cutoff = now.minus(properties.stuckThreshold());
            log.debug("Scanning for stuck CANCELLING orders modified before {}", cutoff);

            return watchdogService.processOrdersByStatus(
                    OrderStatus.CANCELLING,
                    cutoff,
                    orderService::compensateOrder,
                    "scheduled compensation"
            );
        } finally {
            isCompensating.set(false);
        }
    }
}
