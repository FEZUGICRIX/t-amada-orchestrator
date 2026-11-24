package com.tamada.travel.orchestrator.service.orchestration;

import com.tamada.travel.orchestrator.model.CompositeOrder;
import com.tamada.travel.orchestrator.model.OrderStatus;
import com.tamada.travel.orchestrator.repository.CompositeOrderRepository;
import com.tamada.travel.orchestrator.service.CompositeOrderService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Единый сервис фонового мониторинга и сопровождения саги (Saga Watchdog).
 * Объединяет обнаружение и обработку зависших заказов в состояниях PROCESSING и CANCELLING
 * через единый канал обработки и единые правила сканирования.
 */
@Service
@Slf4j
public class SagaWatchdogService {

    private final CompositeOrderRepository repository;
    private final CompositeOrderService orderService;
    private final Clock clock;

    @Autowired
    public SagaWatchdogService(
            CompositeOrderRepository repository,
            CompositeOrderService orderService,
            @Autowired(required = false) Clock clock
    ) {
        this.repository = Objects.requireNonNull(repository, "repository must not be null");
        this.orderService = Objects.requireNonNull(orderService, "orderService must not be null");
        this.clock = clock != null ? clock : Clock.systemUTC();
    }

    public SagaWatchdogService(
            CompositeOrderRepository repository,
            CompositeOrderService orderService
    ) {
        this(repository, orderService, Clock.systemUTC());
    }

    /**
     * Выполняет комплексное обслуживание зависших заказов всех промежуточных состояний.
     */
    public int performMaintenance(Duration stuckThreshold) {
        Instant now = Instant.now(clock);
        Duration threshold = stuckThreshold != null ? stuckThreshold : Duration.ofMinutes(2);
        Instant cutoff = now.minus(threshold);

        int processed = 0;
        processed += processOrdersByStatus(OrderStatus.PROCESSING, cutoff, orderService::recoverOrder, "recovery");
        processed += processOrdersByStatus(OrderStatus.CANCELLING, cutoff, orderService::compensateOrder, "compensation");
        return processed;
    }

    /**
     * Обрабатывает зависшие заказы конкретного статуса постранично (Batch Processing),
     * предотвращая Out of Memory при большом количестве зависших саг.
     */
    public int processOrdersByStatus(
            OrderStatus status,
            Instant cutoff,
            Consumer<UUID> action,
            String operationName
    ) {
        return processOrdersByStatus(status, cutoff, action, operationName, 100);
    }

    public int processOrdersByStatus(
            OrderStatus status,
            Instant cutoff,
            Consumer<UUID> action,
            String operationName,
            int batchSize
    ) {
        int totalProcessed = 0;
        int maxBatches = 20;
        int batchCount = 0;
        java.util.Set<UUID> attemptedOrderIds = new java.util.HashSet<>();

        while (batchCount < maxBatches) {
            List<CompositeOrder> stuckOrders = fetchStuckOrders(status, cutoff, batchSize);
            if (stuckOrders == null || stuckOrders.isEmpty()) {
                break;
            }

            List<CompositeOrder> unhandled = stuckOrders.stream()
                    .filter(o -> !attemptedOrderIds.contains(o.getId()))
                    .toList();
            if (unhandled.isEmpty()) {
                break;
            }

            batchCount++;
            log.info("Watchdog found {} stuck {} order(s) for {} (batch {}/{})",
                    unhandled.size(), status, operationName, batchCount, maxBatches);

            int batchSuccessCount = 0;
            for (CompositeOrder order : unhandled) {
                attemptedOrderIds.add(order.getId());
                try {
                    log.info("Watchdog starting {} for order id={}", operationName, order.getId());
                    action.accept(order.getId());
                    batchSuccessCount++;
                    log.info("Watchdog successfully finished {} for order id={}", operationName, order.getId());
                } catch (Exception ex) {
                    log.error("Watchdog {} failed for order id={}: {}", operationName, order.getId(), ex.getMessage(), ex);
                }
            }

            totalProcessed += batchSuccessCount;
            if (stuckOrders.size() < batchSize) {
                break;
            }
        }

        return totalProcessed;
    }

    private List<CompositeOrder> fetchStuckOrders(OrderStatus status, Instant cutoff, int batchSize) {
        try {
            org.springframework.data.domain.Pageable pageable = org.springframework.data.domain.PageRequest.of(0, batchSize);
            List<CompositeOrder> page = repository.findByStatusAndUpdatedAtBeforeOrderByUpdatedAtAsc(status, cutoff, pageable);
            if (page != null && !page.isEmpty()) {
                return page;
            }
        } catch (Exception ex) {
            log.trace("Paginated query not available or mocked, falling back: {}", ex.getMessage());
        }
        return repository.findByStatusAndUpdatedAtBeforeOrderByUpdatedAtAsc(status, cutoff);
    }
}
