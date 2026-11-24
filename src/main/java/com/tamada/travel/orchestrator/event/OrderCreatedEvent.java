package com.tamada.travel.orchestrator.event;

import com.tamada.travel.orchestrator.model.CompositeOrder;

import java.util.Objects;
import java.util.UUID;

/**
 * Внутреннее доменное событие создания нового составного заказа.
 */
public record OrderCreatedEvent(
        UUID orderId,
        CompositeOrder order
) {
    public OrderCreatedEvent {
        Objects.requireNonNull(orderId, "orderId must not be null");
        Objects.requireNonNull(order, "order must not be null");
    }
}
