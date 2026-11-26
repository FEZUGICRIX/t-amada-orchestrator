package com.tamada.travel.orchestrator.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.util.UUID;

@Schema(description = "Информация о забронированном варианте номера и тарифа в отеле")
@JsonInclude(JsonInclude.Include.NON_NULL)
public record HotelRoomResponse(
        @Schema(description = "Уникальный идентификатор номера", example = "0199f000-0000-7000-8000-000000000010")
        UUID id,

        @Schema(description = "Идентификатор выбранного варианта номера/тарифа в каталоге отеля", example = "var-luxe-bb-flex")
        @JsonProperty("variantId")
        String variantId,

        @Schema(description = "Категория номера", example = "Люкс")
        String category,

        @Schema(description = "Тариф проживания", example = "С завтраком")
        String tariff,

        @Schema(description = "Тип питания", example = "BB")
        @JsonProperty("mealType")
        String mealType,

        @Schema(description = "Стоимость номера", example = "8500.00")
        BigDecimal price,

        @Schema(description = "Условия отмены бронирования", example = "Бесплатная отмена до 14:00 дня заезда")
        @JsonProperty("cancellationPolicy")
        String cancellationPolicy,

        @Schema(description = "Условия проживания", example = "Завтрак включен, бесплатная отмена")
        @JsonProperty("stayConditions")
        String stayConditions
) {
    public HotelRoomResponse(
            UUID id,
            String category,
            String tariff,
            BigDecimal price,
            String stayConditions
    ) {
        this(id, null, category, tariff, null, price, null, stayConditions);
    }
}
