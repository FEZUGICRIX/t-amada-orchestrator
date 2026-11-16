package com.tamada.travel.orchestrator.dto.error;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Подробная техническая информация об ошибке от внешнего сервиса поставщика")
@JsonInclude(JsonInclude.Include.NON_NULL)
public record StepErrorDetailsResponse(
        @Schema(description = "HTTP статус код ошибки от внешнего сервиса", example = "409")
        Integer statusCode,

        @Schema(description = "Категория или заголовок ошибки", example = "Conflict")
        String error,

        @Schema(description = "Исходное техническое сообщение об ошибке от поставщика", example = "Resource is unavailable")
        String message,

        @Schema(description = "URL путь эндпоинта поставщика, вернувшего ошибку", example = "/api/v1/hotel/book")
        String path,

        @Schema(description = "Временная метка ошибки от поставщика", example = "2026-10-18T10:00:00Z")
        String timestamp
) {
}
