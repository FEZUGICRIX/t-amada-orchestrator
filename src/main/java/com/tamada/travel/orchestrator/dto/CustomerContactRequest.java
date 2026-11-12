package com.tamada.travel.orchestrator.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

@Schema(description = "Контакты заказчика поездки")
public record CustomerContactRequest(

        @Schema(description = "Номер телефона заказчика", example = "+79991234567")
        @NotBlank(message = "Phone must not be blank")
        String phone,

        @Schema(description = "Электронная почта заказчика", example = "customer@example.com")
        @NotBlank(message = "Email must not be blank")
        @Email(message = "Email must be a valid email address")
        String email
) {
}
