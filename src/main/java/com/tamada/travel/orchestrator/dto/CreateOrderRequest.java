package com.tamada.travel.orchestrator.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

public record CreateOrderRequest(

        @NotEmpty(message = "Bookings must not be empty")
        @Size(max = 4, message = "Bookings must contain no more than 4 items")
        List<@NotNull(message = "Booking must not be null") @Valid CreateBookingStepRequest> bookings

) {
}
