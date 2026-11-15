package com.tamada.travel.orchestrator.client;

import com.tamada.travel.orchestrator.client.dto.BookingRequest;
import com.tamada.travel.orchestrator.client.dto.BookingResponse;
import com.tamada.travel.orchestrator.client.dto.CancelBookingRequest;
import com.tamada.travel.orchestrator.client.dto.CancelBookingResponse;
import com.tamada.travel.orchestrator.client.dto.ExternalServiceErrorResponse;
import com.tamada.travel.orchestrator.exception.ExternalBookingErrorType;
import com.tamada.travel.orchestrator.exception.ExternalBookingException;
import com.tamada.travel.orchestrator.model.BookingType;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.net.URI;

public class RestBookingClient implements BookingClient {

    private final BookingType type;
    private final RestClient restClient;
    private final String bookingPath;
    private final String cancellationPath;

    public RestBookingClient(
            BookingType type,
            RestClient.Builder builder,
            URI baseUrl,
            String bookingPath,
            String cancellationPath
    ) {
        this.type = type;
        this.restClient = builder.baseUrl(baseUrl).build();
        this.bookingPath = bookingPath;
        this.cancellationPath = cancellationPath;
    }

    @Override
    public BookingType type() {
        return type;
    }

    @Override
    public BookingResponse book(BookingRequest request) {
        try {
            return restClient.post()
                    .uri(bookingPath)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(request)
                    .retrieve()
                    .body(BookingResponse.class);
        } catch (RestClientResponseException exception) {
            throw mapException(exception, bookingPath);
        } catch (ResourceAccessException exception) {
            throw mapResourceAccessException(exception, bookingPath);
        }
    }

    @Override
    public CancelBookingResponse cancel(CancelBookingRequest request) {
        try {
            return restClient.post()
                    .uri(cancellationPath)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(request)
                    .retrieve()
                    .body(CancelBookingResponse.class);
        } catch (RestClientResponseException exception) {
            throw mapException(exception, cancellationPath);
        } catch (ResourceAccessException exception) {
            throw mapResourceAccessException(exception, cancellationPath);
        }
    }

    private ExternalBookingException mapResourceAccessException(
            ResourceAccessException exception,
            String requestPath
    ) {
        return new ExternalBookingException(
                HttpStatus.GATEWAY_TIMEOUT,
                type,
                ExternalBookingErrorType.SYSTEM_FAILURE,
                "External service unavailable or timed out: " + exception.getMessage(),
                requestPath,
                exception
        );
    }

    private ExternalBookingException mapException(
            RestClientResponseException exception,
            String requestPath
    ) {
        ExternalServiceErrorResponse errorResponse = parseErrorResponse(exception);
        String message = errorResponse == null
                ? exception.getStatusText()
                : errorResponse.messageText();
        String path = (errorResponse != null && errorResponse.path() != null && !errorResponse.path().isBlank())
                ? errorResponse.path()
                : requestPath;

        if (message == null || message.isBlank()) {
            message = exception.getStatusText();
        }
        if (message == null || message.isBlank()) {
            message = "External service error (" + exception.getStatusCode().value() + ")";
        }

        return new ExternalBookingException(
                exception.getStatusCode(),
                type,
                errorType(exception.getStatusCode().value()),
                message,
                path,
                exception
        );
    }

    private ExternalServiceErrorResponse parseErrorResponse(
            RestClientResponseException exception
    ) {
        try {
            return exception.getResponseBodyAs(ExternalServiceErrorResponse.class);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private ExternalBookingErrorType errorType(int statusCode) {
        if (statusCode >= 500 && statusCode < 600) {
            return ExternalBookingErrorType.SYSTEM_FAILURE;
        }
        return switch (statusCode) {
            case 400 -> ExternalBookingErrorType.VALIDATION_ERROR;
            case 404 -> ExternalBookingErrorType.NOT_FOUND;
            case 409 -> ExternalBookingErrorType.RESOURCE_UNAVAILABLE;
            default -> ExternalBookingErrorType.UNKNOWN;
        };
    }
}
