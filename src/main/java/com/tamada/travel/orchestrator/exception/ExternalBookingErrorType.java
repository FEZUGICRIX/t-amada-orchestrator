package com.tamada.travel.orchestrator.exception;

public enum ExternalBookingErrorType {
    VALIDATION_ERROR(false),
    NOT_FOUND(false),
    RESOURCE_UNAVAILABLE(false),
    SYSTEM_FAILURE(true),
    UNKNOWN(false);

    private final boolean retryable;

    ExternalBookingErrorType(boolean retryable) {
        this.retryable = retryable;
    }

    public boolean isRetryable() {
        return retryable;
    }
}
