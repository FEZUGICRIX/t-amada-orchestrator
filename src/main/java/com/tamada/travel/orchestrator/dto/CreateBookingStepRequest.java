package com.tamada.travel.orchestrator.dto;

import com.tamada.travel.orchestrator.model.BookingType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

import java.math.BigDecimal;
import java.util.UUID;

@Schema(description = "Запрос на добавление шага бронирования")
public record CreateBookingStepRequest(
        @Schema(description = "Тип бронирования (FLIGHT, HOTEL, INSURANCE, TRANSFER)", example = "FLIGHT")
        @NotNull(message = "Booking type must not be null")
        BookingType type,

        @Schema(description = "Идентификатор бронируемого ресурса во внешнем сервисе (UUID v4 из каталога сервиса)", example = "a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a11")
        @NotNull(message = "Resource id must not be null")
        UUID resourceId,

        @Schema(description = "Стоимость услуги", example = "15000.00")
        @PositiveOrZero(message = "Price must not be negative")
        BigDecimal price
) {
    public CreateBookingStepRequest(BookingType type, UUID resourceId) {
        this(type, resourceId, BigDecimal.ZERO);
    }
}
