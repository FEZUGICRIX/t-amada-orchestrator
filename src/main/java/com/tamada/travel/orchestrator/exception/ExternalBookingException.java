package com.tamada.travel.orchestrator.exception;

import com.tamada.travel.orchestrator.model.BookingType;
import org.springframework.http.HttpStatusCode;

public class ExternalBookingException extends RuntimeException {

    private final HttpStatusCode httpStatus;
    private final BookingType service;
    private final ExternalBookingErrorType errorType;
    private final String path;
    private final String errorTitle;
    private final String supplierTimestamp;
    private final String supplierMessage;
    private final String userFriendlyMessage;

    public ExternalBookingException(
            HttpStatusCode httpStatus,
            BookingType service,
            ExternalBookingErrorType errorType,
            String message,
            String path,
            Throwable cause
    ) {
        this(httpStatus, service, errorType, message, path, null, null, null, null, cause);
    }

    public ExternalBookingException(
            HttpStatusCode httpStatus,
            BookingType service,
            ExternalBookingErrorType errorType,
            String message,
            String path,
            String errorTitle,
            String supplierTimestamp,
            String supplierMessage,
            String userFriendlyMessage,
            Throwable cause
    ) {
        super(message, cause);
        this.httpStatus = httpStatus;
        this.service = service;
        this.errorType = errorType;
        this.path = path;
        this.errorTitle = errorTitle;
        this.supplierTimestamp = supplierTimestamp;
        this.supplierMessage = supplierMessage != null ? supplierMessage : message;
        this.userFriendlyMessage = userFriendlyMessage;
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

    public String getErrorTitle() {
        return errorTitle;
    }

    public String getSupplierTimestamp() {
        return supplierTimestamp;
    }

    public String getSupplierMessage() {
        return supplierMessage;
    }

    public String getUserFriendlyMessage() {
        return userFriendlyMessage;
    }

    public boolean isRetryable() {
        if ("CircuitBreakerOpen".equalsIgnoreCase(errorTitle)
                || (getCause() != null && getCause().getClass().getName().contains("CallNotPermittedException"))) {
            return false;
        }
        return errorType.isRetryable();
    }
}
