package com.tamada.travel.orchestrator.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

import java.math.BigDecimal;

@Schema(description = "Параметры бронируемого номера в отеле")
public record HotelRoomRequest(
        @Schema(description = "Категория номера (например: Стандарт, Люкс, Deluxe, Superior)", example = "Люкс")
        @NotBlank(message = "Room category must not be empty")
        String category,

        @Schema(description = "Тариф проживания (например: Базовый, С завтраком, Все включено, Невозвратный)", example = "С завтраком")
        @NotBlank(message = "Room tariff must not be empty")
        String tariff,

        @Schema(description = "Стоимость проживания в номере", example = "8500.00")
        @NotNull(message = "Room price must not be null")
        @PositiveOrZero(message = "Room price must not be negative")
        BigDecimal price,

        @Schema(description = "Условия проживания (например: Завтрак включен, бесплатная отмена до 24 часов, заезд с 14:00)", example = "Завтрак 'шведский стол' включен, бесплатная отмена до 14:00 дня заезда")
        @JsonProperty("stayConditions")
        @JsonAlias({"stay_conditions", "conditions", "livingConditions", "living_conditions"})
        String stayConditions
) {
    public HotelRoomRequest(String category, String tariff, BigDecimal price) {
        this(category, tariff, price, null);
    }
}
