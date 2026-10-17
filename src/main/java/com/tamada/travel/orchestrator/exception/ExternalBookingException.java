package com.tamada.travel.orchestrator.exception;

import com.tamada.travel.orchestrator.model.BookingType;
import org.springframework.http.HttpStatusCode;

public class ExternalBookingException extends RuntimeException {

    private final HttpStatusCode httpStatus;
    private final BookingType service;
    private final ExternalBookingErrorType errorType;
    private final String path;

    public ExternalBookingException(
            HttpStatusCode httpStatus,
            BookingType service,
            ExternalBookingErrorType errorType,
            String message,
            String path,
            Throwable cause
    ) {
        super(message, cause);
        this.httpStatus = httpStatus;
        this.service = service;
        this.errorType = errorType;
        this.path = path;
    }

    public HttpStatusCode getHttpStatus() {
        return httpStatus;
    }

    public BookingType getService() {
        return service;
    }

    public ExternalBookingErrorType getErrorType() {
        return errorType;
    }

    public String getPath() {
        return path;
    }

    public boolean isRetryable() {
        return errorType.isRetryable();
    }
}
