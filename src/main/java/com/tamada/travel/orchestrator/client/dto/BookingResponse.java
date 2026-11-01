package com.tamada.travel.orchestrator.client.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;
import java.util.UUID;

@JsonIgnoreProperties(ignoreUnknown = true)
public record BookingResponse(
        UUID id,
        UUID bookingReference,
        String status,
        Instant createdAt
) {
}
