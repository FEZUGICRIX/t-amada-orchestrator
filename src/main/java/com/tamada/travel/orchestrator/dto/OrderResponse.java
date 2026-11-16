package com.tamada.travel.orchestrator.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.tamada.travel.orchestrator.model.OrderStatus;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Schema(description = "Информация о составном заказе путешествия")
@JsonInclude(JsonInclude.Include.NON_NULL)
public record OrderResponse(
        @Schema(description = "Уникальный идентификатор заказа", example = "01a10131-40db-780d-8f1c-9a4b0789cfd3")
        UUID id,

        @Schema(description = "Статус заказа (NEW, PROCESSING, COMPLETED, FAILED, CANCELLING, CANCELLED)", example = "NEW")
        OrderStatus status,

        @Schema(description = "Дата и время создания заказа", example = "2026-10-31T18:00:00Z")
        Instant createdAt,

        @Schema(description = "Дата и время последнего обновления заказа", example = "2026-10-31T18:05:00Z")
        Instant updatedAt,

        @Schema(description = "Базовая стоимость заказа (сумма всех шагов)", example = "10000.00")
        BigDecimal basePrice,

        @Schema(description = "Сервисный сбор (0.67% от полной стоимости заказа)", example = "67.00")
        BigDecimal serviceFee,

        @Schema(description = "Итоговая стоимость заказа с учётом сервисного сбора", example = "10067.00")
        BigDecimal totalPrice,

        @Schema(description = "Список шагов бронирования заказа")
        List<BookingStepResponse> bookingSteps,

        @Schema(description = "Список пассажиров заказа")
        @JsonProperty("passengers")
        List<PassengerResponse> passengers,

        @Schema(description = "Контакты заказчика")
        @JsonProperty("customerContact")
        CustomerContactResponse customerContact,

        @Schema(description = "Причина сбоя или отмены заказа", example = "Заказ отменен из-за ошибки бронирования шага HOTEL: Resource is unavailable")
        String failureReason,

        @Schema(description = "Понятное пользователю сообщение об ошибке или отмене заказа", example = "Заказ не может быть выполнен: выбранный отель недоступен. Ранее забронированные услуги отменены.")
        String userMessage,

        @Schema(description = "История операций, сбоев и отмен по заказу")
        List<OrderOperationHistoryResponse> operationHistory
) {
    public OrderResponse(
            UUID id,
            OrderStatus status,
            Instant createdAt,
            Instant updatedAt,
            List<BookingStepResponse> bookingSteps
    ) {
        this(id, status, createdAt, updatedAt, bookingSteps, List.of(), null);
    }

    public OrderResponse(
            UUID id,
            OrderStatus status,
            Instant createdAt,
            Instant updatedAt,
            List<BookingStepResponse> bookingSteps,
            List<PassengerResponse> passengers,
            CustomerContactResponse customerContact
    ) {
        this(
                id,
                status,
                createdAt,
                updatedAt,
                BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP),
                BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP),
                BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP),
                bookingSteps,
                passengers,
                customerContact
        );
    }

    public OrderResponse(
            UUID id,
            OrderStatus status,
            Instant createdAt,
            Instant updatedAt,
            BigDecimal basePrice,
            BigDecimal serviceFee,
            BigDecimal totalPrice,
            List<BookingStepResponse> bookingSteps,
            List<PassengerResponse> passengers,
            CustomerContactResponse customerContact
    ) {
        this(
                id,
                status,
                createdAt,
                updatedAt,
                basePrice,
                serviceFee,
                totalPrice,
                bookingSteps,
                passengers,
                customerContact,
                null,
                null,
                List.of()
        );
    }
}
