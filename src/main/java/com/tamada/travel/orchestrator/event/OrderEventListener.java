package com.tamada.travel.orchestrator.event;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Слушатель внутренних доменных событий заказа.
 * Демонстрирует объединение потока событий: компоненты могут независимо подписываться
 * на события жизненного цикла заказа без прямого связывания с сервисами оркестрации.
 */
@Component
@Slf4j
public class OrderEventListener {

    @EventListener
    public void onOrderCreated(OrderCreatedEvent event) {
        log.debug("Domain event: order created id={}", event.orderId());
    }

    @EventListener
    public void onOrderStatusChanged(OrderStatusChangedEvent event) {
        log.debug("Domain event: order status changed id={} status={}", event.orderId(), event.status());
    }

    @EventListener
    public void onStepStatusChanged(BookingStepStatusChangedEvent event) {
        log.debug("Domain event: step status changed orderId={} stepId={} type={} status={}",
                event.orderId(), event.stepId(), event.type(), event.status());
    }

    @EventListener
    public void onStepFailed(BookingStepFailedEvent event) {
        log.warn("Domain event: step failed orderId={} stepId={} type={} errorCode={}",
                event.orderId(), event.stepId(), event.type(), event.errorCode());
    }

    @EventListener
    public void onStepCancelled(BookingStepCancelledEvent event) {
        log.debug("Domain event: step cancelled orderId={} stepId={} type={} reason={}",
                event.orderId(), event.stepId(), event.type(), event.cancellationReason());
    }
}
