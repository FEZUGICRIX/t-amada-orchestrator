package com.tamada.travel.orchestrator.client.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;
import java.util.UUID;

@JsonIgnoreProperties(ignoreUnknown = true)
public record CancelBookingResponse(
        UUID id,
        String status,
        Instant updatedAt
) {
}
