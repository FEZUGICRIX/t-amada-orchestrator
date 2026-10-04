package com.tamada.travel.orchestrator.dto;

import com.tamada.travel.orchestrator.model.OrderStatus;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record OrderResponse(
        UUID id,
        OrderStatus status,
        Instant createdAt,
        Instant updatedAt,
        List<BookingStepResponse> bookingSteps
) {
}