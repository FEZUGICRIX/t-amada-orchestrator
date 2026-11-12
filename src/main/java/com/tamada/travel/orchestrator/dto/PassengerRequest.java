package com.tamada.travel.orchestrator.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.tamada.travel.orchestrator.model.PassengerType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Past;

import java.time.LocalDate;

@Schema(description = "Данные пассажира для бронирования")
public record PassengerRequest(

        @Schema(description = "Тип пассажира (ADULT, CHILD, INFANT)", example = "ADULT")
        @NotNull(message = "Passenger type must not be null")
        PassengerType type,

        @Schema(description = "Имя пассажира", example = "Иван")
        @NotBlank(message = "First name must not be blank")
        @JsonAlias({"first_name", "firstName"})
        String firstName,

        @Schema(description = "Фамилия пассажира", example = "Иванов")
        @NotBlank(message = "Last name must not be blank")
        @JsonAlias({"last_name", "lastName"})
        String lastName,

        @Schema(description = "Дата рождения пассажира", example = "1990-05-15")
        @NotNull(message = "Birth date must not be null")
        @Past(message = "Birth date must be in the past")
        @JsonAlias({"birth_date", "birthDate"})
        LocalDate birthDate,

        @Schema(description = "Гражданство", example = "Россия")
        @NotBlank(message = "Citizenship must not be blank")
        String citizenship,

        @Schema(description = "Тип документа (Паспорт РФ, Свидетельство о рождении)", example = "Паспорт РФ")
        @NotBlank(message = "Document type must not be blank")
        @JsonAlias({"document_type", "documentType"})
        String documentType,

        @Schema(description = "Номер документа", example = "4510 123456")
        @NotBlank(message = "Document number must not be blank")
        @JsonAlias({"document_number", "documentNumber"})
        String documentNumber
) {
}
