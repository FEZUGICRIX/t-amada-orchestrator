package com.tamada.travel.orchestrator.event;

import com.tamada.travel.orchestrator.model.BookingStatus;
import com.tamada.travel.orchestrator.model.BookingType;

import java.util.Objects;
import java.util.UUID;

/**
 * Внутреннее доменное событие отмены или компенсации шага бронирования.
 */
public record BookingStepCancelledEvent(
        UUID orderId,
        UUID stepId,
        BookingType type,
        BookingStatus status,
        String cancellationReason,
        String userMessage
) {
    public BookingStepCancelledEvent {
        Objects.requireNonNull(orderId, "orderId must not be null");
        Objects.requireNonNull(stepId, "stepId must not be null");
        Objects.requireNonNull(type, "type must not be null");
        Objects.requireNonNull(status, "status must not be null");
    }
}
