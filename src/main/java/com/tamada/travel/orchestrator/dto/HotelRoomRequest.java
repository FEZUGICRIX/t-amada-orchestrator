package com.tamada.travel.orchestrator.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

import java.math.BigDecimal;

@Schema(description = "Параметры выбранного варианта номера и тарифа в отеле")
public record HotelRoomRequest(
        @Schema(description = "Идентификатор выбранного варианта номера/тарифа в каталоге отеля", example = "var-luxe-bb-flex")
        @JsonProperty("variantId")
        @JsonAlias({"variant_id", "roomVariantId", "room_variant_id", "rateId", "rate_id", "optionId", "option_id"})
        String variantId,

        @Schema(description = "Категория номера (например: Стандарт, Люкс, Deluxe, Superior)", example = "Люкс")
        @NotBlank(message = "Room category must not be empty")
        String category,

        @Schema(description = "Тариф проживания (например: Базовый, С завтраком, Все включено, Невозвратный)", example = "С завтраком")
        @NotBlank(message = "Room tariff must not be empty")
        String tariff,

        @Schema(description = "Тип питания (например: RO, BB, HB, FB, AI, Без питания, Завтрак включён)", example = "BB")
        @JsonProperty("mealType")
        @JsonAlias({"meal_type", "boardType", "board_type", "mealPlan", "meal_plan"})
        String mealType,

        @Schema(description = "Стоимость проживания в номере", example = "8500.00")
        @NotNull(message = "Room price must not be null")
        @PositiveOrZero(message = "Room price must not be negative")
        BigDecimal price,

        @Schema(description = "Условия отмены бронирования (например: Бесплатная отмена до 24 часов до заезда, Невозвратный тариф)", example = "Бесплатная отмена до 14:00 дня заезда")
        @JsonProperty("cancellationPolicy")
        @JsonAlias({"cancellation_policy", "cancellationConditions", "cancellation_conditions", "cancelPolicy", "cancel_policy"})
        String cancellationPolicy,

        @Schema(description = "Условия проживания (например: Завтрак включен, бесплатная отмена до 24 часов, заезд с 14:00)", example = "Завтрак 'шведский стол' включен, заезд с 14:00")
        @JsonProperty("stayConditions")
        @JsonAlias({"stay_conditions", "conditions", "livingConditions", "living_conditions"})
        String stayConditions
) {
    public HotelRoomRequest(String category, String tariff, BigDecimal price) {
        this(null, category, tariff, null, price, null, null);
    }

    public HotelRoomRequest(String category, String tariff, BigDecimal price, String stayConditions) {
        this(null, category, tariff, null, price, null, stayConditions);
    }
}
