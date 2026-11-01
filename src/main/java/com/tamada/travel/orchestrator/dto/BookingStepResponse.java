package com.tamada.travel.orchestrator.dto;

import com.tamada.travel.orchestrator.model.BookingStatus;
import com.tamada.travel.orchestrator.model.BookingType;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.UUID;

@Schema(description = "Информация о шаге бронирования в составе заказа")
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
        String externalId
) {
}
