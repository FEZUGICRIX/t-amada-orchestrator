package com.tamada.travel.orchestrator.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.UUID;

@Schema(description = "Контакты заказчика")
public record CustomerContactResponse(
        @Schema(description = "Уникальный идентификатор контакта", example = "01a10131-40db-780d-8f1c-9a4b0789cfd3")
        UUID id,

        @Schema(description = "Телефон", example = "+79991234567")
        String phone,

        @Schema(description = "Электронная почта", example = "customer@example.com")
        String email
) {
}
