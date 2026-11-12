package com.tamada.travel.orchestrator.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
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
                schema = @Schema(description = "Шаг бронирования (рейс туда, рейс обратно, отель, страховка, трансфер)"),
                minItems = 1,
                maxItems = 5
        )
        @NotEmpty(message = "Bookings must not be empty")
        @Size(max = 5, message = "Bookings must contain no more than 5 items")
        List<@NotNull(message = "Booking must not be null") @Valid CreateBookingStepRequest> bookings,

        @ArraySchema(
                schema = @Schema(description = "Список пассажиров поездки")
        )
        @JsonAlias({"passengers"})
        List<@NotNull(message = "Passenger must not be null") @Valid PassengerRequest> passengers,

        @Schema(description = "Контакты заказчика поездки")
        @JsonAlias({"customer_contact", "customerContact"})
        @Valid CustomerContactRequest customerContact

) {
    public CreateOrderRequest(List<CreateBookingStepRequest> bookings) {
        this(bookings, null, null);
    }
}
