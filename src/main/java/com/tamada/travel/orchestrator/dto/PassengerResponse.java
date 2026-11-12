package com.tamada.travel.orchestrator.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.tamada.travel.orchestrator.model.PassengerType;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDate;
import java.util.UUID;

@Schema(description = "Информация о пассажире")
public record PassengerResponse(
        @Schema(description = "Уникальный идентификатор пассажира", example = "01a10131-40db-780d-8f1c-9a4b0789cfd3")
        UUID id,

        @Schema(description = "Тип пассажира (ADULT, CHILD, INFANT)", example = "ADULT")
        PassengerType type,

        @Schema(description = "Имя пассажира", example = "Иван")
        @JsonProperty("firstName")
        String firstName,

        @Schema(description = "Фамилия пассажира", example = "Иванов")
        @JsonProperty("lastName")
        String lastName,

        @Schema(description = "Дата рождения пассажира", example = "1990-05-15")
        @JsonProperty("birthDate")
        LocalDate birthDate,

        @Schema(description = "Гражданство", example = "Россия")
        String citizenship,

        @Schema(description = "Тип документа", example = "Паспорт РФ")
        @JsonProperty("documentType")
        String documentType,

        @Schema(description = "Номер документа", example = "4510 123456")
        @JsonProperty("documentNumber")
        String documentNumber
) {
}
