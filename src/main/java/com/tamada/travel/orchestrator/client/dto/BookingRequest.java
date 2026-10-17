package com.tamada.travel.orchestrator.client.dto;

import java.util.UUID;

public record BookingRequest(
        UUID resourceId,
        UUID bookingReference
) {
}
