package com.tamada.travel.orchestrator.client.dto;

import java.util.UUID;

public record CancelBookingRequest(
        UUID bookingId
) {
}
