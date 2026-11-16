package com.tamada.travel.orchestrator.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.tamada.travel.orchestrator.dto.error.StepErrorDetailsResponse;
import com.tamada.travel.orchestrator.model.BookingType;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.UUID;

@Schema(description = "Запись в истории операций и неудачных попыток по заказу")
@JsonInclude(JsonInclude.Include.NON_NULL)
public record OrderOperationHistoryResponse(
        @Schema(description = "Уникальный идентификатор записи истории", example = "0199f000-0000-7000-8000-000000000099")
        UUID id,

        @Schema(description = "Идентификатор шага бронирования (null для операций уровня заказа)", example = "0199f000-0000-7000-8000-000000000002")
        UUID bookingStepId,

        @Schema(description = "Тип услуги (FLIGHT, HOTEL, INSURANCE, TRANSFER)", example = "HOTEL")
        BookingType bookingType,

        @Schema(description = "Тип операции (BOOK, CANCEL, COMPENSATION, SKIP, MANUAL_CANCEL, ORDER_CANCELLATION)", example = "BOOK")
        String operationType,

        @Schema(description = "Статус операции (FAILED, CANCELLED, SKIPPED, COMPLETED)", example = "FAILED")
        String status,

        @Schema(description = "HTTP статус ошибки от поставщика", example = "409")
        Integer httpStatus,

        @Schema(description = "Код ошибки (RESOURCE_UNAVAILABLE, NOT_FOUND, VALIDATION_ERROR, SYSTEM_FAILURE)", example = "RESOURCE_UNAVAILABLE")
        String errorCode,

        @Schema(description = "Техническое сообщение об ошибке", example = "Resource is unavailable")
        String message,

        @Schema(description = "Понятное пользователю сообщение об ошибке или отмене", example = "Выбранный номер в отеле недоступен для бронирования на указанные даты.")
        String userMessage,

        @Schema(description = "Причина отмены услуги или остальных услуг заказа", example = "Отменено: компенсация бронирования в связи со сбоем отеля")
        String cancellationReason,

        @Schema(description = "Подробные технические данные ошибки поставщика")
        StepErrorDetailsResponse errorDetails,

        @Schema(description = "Время фиксации операции", example = "2026-10-31T18:05:00Z")
        Instant createdAt
) {
}
