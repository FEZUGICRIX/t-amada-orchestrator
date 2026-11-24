package com.tamada.travel.orchestrator.event;

import com.tamada.travel.orchestrator.model.OrderStatus;

import java.util.Objects;
import java.util.UUID;

/**
 * Внутреннее доменное событие изменения глобального статуса заказа.
 */
public record OrderStatusChangedEvent(
        UUID orderId,
        OrderStatus status,
        String failureReason,
        String userMessage
) {
    public OrderStatusChangedEvent {
        Objects.requireNonNull(orderId, "orderId must not be null");
        Objects.requireNonNull(status, "status must not be null");
    }

    public OrderStatusChangedEvent(UUID orderId, OrderStatus status) {
        this(orderId, status, null, null);
    }
}
