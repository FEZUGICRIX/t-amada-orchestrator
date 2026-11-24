package com.tamada.travel.orchestrator.event;

import com.tamada.travel.orchestrator.dto.error.StepErrorDetailsResponse;
import com.tamada.travel.orchestrator.model.BookingType;

import java.util.Objects;
import java.util.UUID;

/**
 * Внутреннее доменное событие сбоя внешнего бронирования шага.
 */
public record BookingStepFailedEvent(
        UUID orderId,
        UUID stepId,
        BookingType type,
        String errorCode,
        Integer httpStatus,
        String errorMessage,
        String userMessage,
        StepErrorDetailsResponse details
) {
    public BookingStepFailedEvent {
        Objects.requireNonNull(orderId, "orderId must not be null");
        Objects.requireNonNull(stepId, "stepId must not be null");
        Objects.requireNonNull(type, "type must not be null");
    }
}
