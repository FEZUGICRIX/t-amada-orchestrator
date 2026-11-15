package com.tamada.travel.orchestrator.service.orchestration;

import com.tamada.travel.orchestrator.client.dto.BookingResponse;
import com.tamada.travel.orchestrator.client.dto.CancelBookingResponse;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Валидатор ответов внешних сервисов бронирования и отмены в рамках Saga Orchestration.
 */
@Component
public class StepResponseValidator {

    private static final String CONFIRMED_STATUS = "CONFIRMED";
    private static final String CANCELLED_STATUS = "CANCELLED";

    public void validateBookingResponse(UUID expectedReference, BookingResponse response) {
        if (response == null) {
            throw new IllegalStateException("External booking returned no response");
        }
        if (response.id() == null) {
            throw new IllegalStateException("External booking id is missing");
        }
        if (!expectedReference.equals(response.bookingReference())) {
            throw new IllegalStateException(
                    "Booking response reference does not match step " + expectedReference
            );
        }
        if (!CONFIRMED_STATUS.equalsIgnoreCase(response.status())) {
            throw new IllegalStateException(
                    "Unexpected external booking status: " + response.status()
            );
        }
    }

    public void validateCancellationResponse(UUID expectedExternalId, CancelBookingResponse response) {
        if (response == null) {
            throw new IllegalStateException("External cancellation returned no response");
        }
        if (!expectedExternalId.equals(response.id())) {
            throw new IllegalStateException(
                    "Cancellation response id does not match external booking " + expectedExternalId
            );
        }
        if (!CANCELLED_STATUS.equalsIgnoreCase(response.status())) {
            throw new IllegalStateException(
                    "Unexpected external cancellation status: " + response.status()
            );
        }
    }
}
