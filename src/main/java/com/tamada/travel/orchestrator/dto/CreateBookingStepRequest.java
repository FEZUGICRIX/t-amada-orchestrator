package com.tamada.travel.orchestrator.dto;

import com.tamada.travel.orchestrator.model.BookingType;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record CreateBookingStepRequest(
        @NotNull(message = "Booking type must not be null")
        BookingType type,

        @NotNull(message = "Resource id must not be null")
        UUID resourceId
) {
}
