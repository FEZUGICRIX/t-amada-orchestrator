package com.tamada.travel.orchestrator.client.dto;

import java.time.Instant;
import java.util.UUID;

public record BookingResponse(
        UUID id,
        UUID bookingReference,
        String status,
        Instant createdAt
) {
}
