package com.tamada.travel.orchestrator.dto;

import com.tamada.travel.orchestrator.model.BookingType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

@Schema(description = "Запрос на добавление шага бронирования")
public record CreateBookingStepRequest(
        @Schema(description = "Тип бронирования (FLIGHT, HOTEL, INSURANCE, TRANSFER)", example = "FLIGHT")
        @NotNull(message = "Booking type must not be null")
        BookingType type,

        @Schema(description = "Идентификатор бронируемого ресурса во внешнем сервисе", example = "10000000-0000-0000-0000-000000000001")
        @NotNull(message = "Resource id must not be null")
        UUID resourceId
) {
}
