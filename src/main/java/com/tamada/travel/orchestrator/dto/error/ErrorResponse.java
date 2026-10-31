package com.tamada.travel.orchestrator.dto.error;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.Map;

@Schema(description = "Формат ответа при ошибке")
public record ErrorResponse(
        @Schema(description = "Время возникновения ошибки", example = "2026-10-18T10:00:00Z")
        Instant timestamp,

        @Schema(description = "HTTP статус", example = "404")
        int status,

        @Schema(description = "Краткое описание ошибки", example = "Not Found")
        String error,

        @Schema(description = "Детальное сообщение об ошибке", example = "Order not found: 01a10131-40db-780d-8f1c-9a4b0789cfd3")
        String message,

        @Schema(description = "Путь вызванного эндпоинта", example = "/api/v1/orders/01a10131-40db-780d-8f1c-9a4b0789cfd3")
        String path,

        @Schema(description = "Ошибки валидации полей (если применимо)")
        Map<String, String> validationErrors
) {
}
