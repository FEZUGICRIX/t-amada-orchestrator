package com.tamada.travel.orchestrator.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.tamada.travel.orchestrator.dto.error.StepErrorDetailsResponse;
import com.tamada.travel.orchestrator.model.BookingStatus;
import com.tamada.travel.orchestrator.model.BookingType;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.UUID;

@Schema(description = "Информация о шаге бронирования в составе заказа")
@JsonInclude(JsonInclude.Include.NON_NULL)
public record BookingStepResponse(
        @Schema(description = "Уникальный идентификатор шага бронирования (bookingReference)", example = "0199f000-0000-7000-8000-000000000002")
        UUID id,

        @Schema(description = "Тип бронирования", example = "FLIGHT")
        BookingType type,

        @Schema(description = "Идентификатор ресурса", example = "a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a11")
        UUID resourceId,

        @Schema(description = "Статус шага бронирования (PENDING, PROCESSING, CONFIRMED, FAILED, CANCELLING, CANCELLED)", example = "CONFIRMED")
        BookingStatus status,

        @Schema(description = "Идентификатор созданной брони во внешнем сервисе (null до подтверждения)", example = "30000000-0000-4000-8000-000000000003")
        String externalId,

        @Schema(description = "Стоимость шага бронирования", example = "15000.00")
        BigDecimal price,

        @Schema(description = "Количество номеров (только для отеля)", example = "2")
        @JsonProperty("roomsCount")
        @JsonInclude(JsonInclude.Include.NON_NULL)
        Integer roomsCount,

        @ArraySchema(schema = @Schema(description = "Список забронированных номеров в отеле"))
        @JsonProperty("rooms")
        @JsonInclude(JsonInclude.Include.NON_EMPTY)
        List<HotelRoomResponse> rooms,

        @Schema(description = "Код ошибки при сбое бронирования", example = "RESOURCE_UNAVAILABLE")
        @JsonInclude(JsonInclude.Include.NON_NULL)
        String errorCode,

        @Schema(description = "Техническое сообщение об ошибке от внешнего сервиса", example = "Resource is unavailable")
        @JsonInclude(JsonInclude.Include.NON_NULL)
        String errorMessage,

        @Schema(description = "Понятное пользователю сообщение об ошибке", example = "Выбранный номер в отеле недоступен для бронирования на указанные даты.")
        @JsonInclude(JsonInclude.Include.NON_NULL)
        String userMessage,

        @Schema(description = "Причина отмены шага (например, при компенсации Саги)", example = "Отменено: компенсация из-за сбоя бронирования отеля")
        @JsonInclude(JsonInclude.Include.NON_NULL)
        String cancellationReason,

        @Schema(description = "Подробная техническая информация об ошибке от поставщика")
        @JsonInclude(JsonInclude.Include.NON_NULL)
        StepErrorDetailsResponse errorDetails
) {
    public BookingStepResponse(
            UUID id,
            BookingType type,
            UUID resourceId,
            BookingStatus status,
            String externalId
    ) {
        this(id, type, resourceId, status, externalId, BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP), null, null, null, null, null, null, null);
    }

    public BookingStepResponse(
            UUID id,
            BookingType type,
            UUID resourceId,
            BookingStatus status,
            String externalId,
            BigDecimal price
    ) {
        this(id, type, resourceId, status, externalId, price, null, null, null, null, null, null, null);
    }

    public BookingStepResponse(
            UUID id,
            BookingType type,
            UUID resourceId,
            BookingStatus status,
            String externalId,
            BigDecimal price,
            Integer roomsCount,
            List<HotelRoomResponse> rooms
    ) {
        this(id, type, resourceId, status, externalId, price, roomsCount, rooms, null, null, null, null, null);
    }
}
