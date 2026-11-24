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

@Schema(description = "Запрос на замену отеля на альтернативный с сохранением остальных услуг")
public record ReplaceHotelRequest(
        @Schema(description = "Тип бронирования (опционально, всегда HOTEL)", example = "HOTEL")
        BookingType type,

        @Schema(description = "Идентификатор альтернативного отеля во внешнем сервисе", example = "b0eebc99-9c0b-4ef8-bb6d-6bb9bd380a12")
        @NotNull(message = "Resource id must not be null")
        UUID resourceId,

        @Schema(description = "Стоимость проживания в отеле", example = "12000.00")
        @PositiveOrZero(message = "Price must not be negative")
        BigDecimal price,

        @Schema(description = "Количество номеров в отеле", example = "2")
        @JsonProperty("roomsCount")
        @JsonAlias({"rooms_count", "roomCount", "room_count"})
        @Positive(message = "Rooms count must be positive")
        Integer roomsCount,

        @ArraySchema(schema = @Schema(description = "Параметры каждого выбранного номера"))
        @JsonProperty("rooms")
        List<@NotNull(message = "Room must not be null") @Valid HotelRoomRequest> rooms
) {
    public ReplaceHotelRequest(UUID resourceId, BigDecimal price) {
        this(BookingType.HOTEL, resourceId, price, null, null);
    }

    public ReplaceHotelRequest(UUID resourceId, BigDecimal price, List<HotelRoomRequest> rooms) {
        this(BookingType.HOTEL, resourceId, price, rooms != null ? rooms.size() : null, rooms);
    }

    public ReplaceHotelRequest(UUID resourceId, BigDecimal price, Integer roomsCount, List<HotelRoomRequest> rooms) {
        this(BookingType.HOTEL, resourceId, price, roomsCount, rooms);
    }

    public CreateBookingStepRequest toCreateBookingStepRequest() {
        return new CreateBookingStepRequest(
                BookingType.HOTEL,
                resourceId,
                price,
                roomsCount,
                rooms
        );
    }
}
