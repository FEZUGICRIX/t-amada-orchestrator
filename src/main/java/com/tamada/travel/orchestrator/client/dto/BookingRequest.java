package com.tamada.travel.orchestrator.client.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Контракт запроса на бронирование услуги во внешнем сервисе поставщика.
 * Содержит TravelTech-информацию: ресурс, ссылку брони, стоимость, валюту,
 * количество требуемых физических мест и количество пассажиров по категориям,
 * параметры номеров отеля и контакты заказчика.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record BookingRequest(
        UUID resourceId,
        UUID bookingReference,
        BigDecimal price,
        String currency,
        Integer seatsCount,
        Integer adultsCount,
        Integer childrenCount,
        Integer infantsCount,
        List<RoomDetail> rooms,
        CustomerContactDetail customerContact
) {
    public BookingRequest(UUID resourceId, UUID bookingReference) {
        this(resourceId, bookingReference, null, null, null, null, null, null, null, null);
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record RoomDetail(
            UUID id,
            String variantId,
            String category,
            String tariff,
            String mealType,
            BigDecimal price,
            String cancellationPolicy,
            String stayConditions
    ) {
        public RoomDetail(
                UUID id,
                String category,
                String tariff,
                BigDecimal price,
                String stayConditions
        ) {
            this(id, null, category, tariff, null, price, null, stayConditions);
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record CustomerContactDetail(
            String phone,
            String email
    ) {
    }
}
