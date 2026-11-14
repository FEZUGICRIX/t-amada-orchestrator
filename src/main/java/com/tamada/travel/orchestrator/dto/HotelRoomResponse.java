package com.tamada.travel.orchestrator.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.util.UUID;

@Schema(description = "Информация о забронированном номере в отеле")
public record HotelRoomResponse(
        @Schema(description = "Уникальный идентификатор номера", example = "0199f000-0000-7000-8000-000000000010")
        UUID id,

        @Schema(description = "Категория номера", example = "Люкс")
        String category,

        @Schema(description = "Тариф проживания", example = "С завтраком")
        String tariff,

        @Schema(description = "Стоимость номера", example = "8500.00")
        BigDecimal price,

        @Schema(description = "Условия проживания", example = "Завтрак включен, бесплатная отмена")
        @JsonProperty("stayConditions")
        String stayConditions
) {
}
