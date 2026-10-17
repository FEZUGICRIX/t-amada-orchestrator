package com.tamada.travel.orchestrator.client.dto;

import java.time.Instant;
import java.util.UUID;

public record CancelBookingResponse(
        UUID id,
        String status,
        Instant updatedAt
) {
}
