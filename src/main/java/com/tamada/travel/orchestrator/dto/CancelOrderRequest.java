package com.tamada.travel.orchestrator.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.UUID;

@Schema(description = "Запрос на отмену заказа или отдельного шага бронирования")
public record CancelOrderRequest(
        @Schema(description = "Идентификатор шага бронирования (если указан, выполняется частичная отмена конкретного шага без отмены всего заказа)", example = "0199f000-0000-7000-8000-000000000002")
        UUID stepId
) {
    public CancelOrderRequest() {
        this(null);
    }
}
