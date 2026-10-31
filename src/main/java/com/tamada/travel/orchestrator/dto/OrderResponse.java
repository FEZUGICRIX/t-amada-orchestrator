package com.tamada.travel.orchestrator.dto;

import com.tamada.travel.orchestrator.model.OrderStatus;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Schema(description = "Информация о составном заказе путешествия")
public record OrderResponse(
        @Schema(description = "Уникальный идентификатор заказа", example = "01a10131-40db-780d-8f1c-9a4b0789cfd3")
        UUID id,

        @Schema(description = "Статус заказа (NEW, PROCESSING, COMPLETED, FAILED, CANCELLING, CANCELLED)", example = "NEW")
        OrderStatus status,

        @Schema(description = "Дата и время создания заказа", example = "2026-10-31T18:00:00Z")
        Instant createdAt,

        @Schema(description = "Дата и время последнего обновления заказа", example = "2026-10-31T18:05:00Z")
        Instant updatedAt,

        @Schema(description = "Список шагов бронирования заказа")
        List<BookingStepResponse> bookingSteps
) {
}
