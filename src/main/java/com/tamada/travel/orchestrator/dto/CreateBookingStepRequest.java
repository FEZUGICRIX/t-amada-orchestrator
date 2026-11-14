package com.tamada.travel.orchestrator.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.tamada.travel.orchestrator.model.BookingType;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;

import java.math.BigDecimal;
import java.util.List;
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
        BigDecimal price,

        @Schema(description = "Количество номеров в отеле", example = "2")
        @JsonProperty("roomsCount")
        @JsonAlias({"rooms_count", "roomCount", "room_count"})
        @Positive(message = "Rooms count must be positive")
        Integer roomsCount,

        @ArraySchema(schema = @Schema(description = "Параметры каждого выбранного номера (только для отеля)"))
        @JsonProperty("rooms")
        List<@NotNull(message = "Room must not be null") @Valid HotelRoomRequest> rooms
) {
    public CreateBookingStepRequest(BookingType type, UUID resourceId) {
        this(type, resourceId, BigDecimal.ZERO, null, null);
    }

    public CreateBookingStepRequest(BookingType type, UUID resourceId, BigDecimal price) {
        this(type, resourceId, price, null, null);
    }

    public CreateBookingStepRequest(
            BookingType type,
            UUID resourceId,
            BigDecimal price,
            List<HotelRoomRequest> rooms
    ) {
        this(type, resourceId, price, rooms != null ? rooms.size() : null, rooms);
    }
}
