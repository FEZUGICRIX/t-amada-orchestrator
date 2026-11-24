package com.tamada.travel.orchestrator.service.error;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tamada.travel.orchestrator.dto.error.StepErrorDetailsResponse;
import com.tamada.travel.orchestrator.exception.ExternalBookingException;
import com.tamada.travel.orchestrator.model.BookingType;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Objects;

/**
 * Преобразователь технических ошибок внешних поставщиков в структурированные детали,
 * понятные пользователю сообщения и JSON-детали для сохранения в БД и SSE-потоках.
 */
@Component
public class StepErrorMapper {

    private final UserErrorMessageResolver messageResolver;
    private final ObjectMapper objectMapper;

    @Autowired
    public StepErrorMapper(
            UserErrorMessageResolver messageResolver,
            @Autowired(required = false) ObjectMapper objectMapper
    ) {
        this.messageResolver = Objects.requireNonNull(messageResolver, "messageResolver must not be null");
        this.objectMapper = objectMapper != null ? objectMapper : new ObjectMapper();
    }

    public StepErrorInfo mapBookingError(BookingType type, RuntimeException exception) {
        if (exception instanceof ExternalBookingException extEx) {
            int status = extEx.getHttpStatus().value();
            String errorCode = extEx.getErrorType().name();
            String technicalMessage = extEx.getMessage();
            String userMessage = extEx.getUserFriendlyMessage() != null
                    ? extEx.getUserFriendlyMessage()
                    : messageResolver.resolveBookingErrorMessage(extEx.getService(), extEx.getErrorType(), status, technicalMessage);

            StepErrorDetailsResponse details = new StepErrorDetailsResponse(
                    status,
                    extEx.getErrorTitle(),
                    extEx.getSupplierMessage(),
                    extEx.getPath(),
                    extEx.getSupplierTimestamp() != null ? extEx.getSupplierTimestamp() : Instant.now().toString()
            );

            return new StepErrorInfo(status, errorCode, technicalMessage, userMessage, details, serialize(details));
        }

        int status = 500;
        String errorCode = "SYSTEM_FAILURE";
        String technicalMessage = exception.getMessage() != null ? exception.getMessage() : "Unknown error";
        String userMessage = "Сбой при бронировании услуги (" + messageResolver.formatServiceName(type) + "): " + technicalMessage;
        StepErrorDetailsResponse details = new StepErrorDetailsResponse(
                status,
                "Internal Error",
                technicalMessage,
                null,
                Instant.now().toString()
        );

        return new StepErrorInfo(status, errorCode, technicalMessage, userMessage, details, serialize(details));
    }

    public String serialize(StepErrorDetailsResponse details) {
        if (details == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(details);
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    public String serializeException(ExternalBookingException exception) {
        if (exception == null) {
            return null;
        }
        StepErrorDetailsResponse details = new StepErrorDetailsResponse(
                exception.getHttpStatus().value(),
                exception.getErrorTitle(),
                exception.getSupplierMessage(),
                exception.getPath(),
                exception.getSupplierTimestamp() != null ? exception.getSupplierTimestamp() : Instant.now().toString()
        );
        return serialize(details);
    }

    public record StepErrorInfo(
            Integer httpStatus,
            String errorCode,
            String technicalMessage,
            String userMessage,
            StepErrorDetailsResponse details,
            String serializedDetailsJson
    ) {
    }
}
