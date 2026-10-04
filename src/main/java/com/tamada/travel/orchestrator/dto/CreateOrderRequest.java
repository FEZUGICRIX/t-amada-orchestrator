package com.tamada.travel.orchestrator.dto;

import com.tamada.travel.orchestrator.model.BookingType;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

public record CreateOrderRequest(

        @NotEmpty(message = "Booking types must not be empty")
        @Size(max = 4, message = "Booking types must contain no more than 4 items")
        List<@NotNull(message = "Booking type must not be null") BookingType> bookingTypes

) {
}