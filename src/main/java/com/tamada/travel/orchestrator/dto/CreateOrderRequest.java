package com.tamada.travel.orchestrator.dto;

import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

@Schema(description = "Запрос на создание нового составного заказа путешествия")
public record CreateOrderRequest(

        @ArraySchema(
                schema = @Schema(description = "Шаг бронирования"),
                minItems = 1,
                maxItems = 4
        )
        @NotEmpty(message = "Bookings must not be empty")
        @Size(max = 4, message = "Bookings must contain no more than 4 items")
        List<@NotNull(message = "Booking must not be null") @Valid CreateBookingStepRequest> bookings

) {
}
