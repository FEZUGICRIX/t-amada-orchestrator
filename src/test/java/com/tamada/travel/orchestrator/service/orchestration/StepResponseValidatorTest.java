package com.tamada.travel.orchestrator.service.orchestration;

import com.tamada.travel.orchestrator.client.dto.BookingResponse;
import com.tamada.travel.orchestrator.client.dto.CancelBookingResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StepResponseValidatorTest {

    private StepResponseValidator validator;

    @BeforeEach
    void setUp() {
        validator = new StepResponseValidator();
    }

    @Test
    void shouldValidateSuccessfulBookingResponse() {
        UUID reference = UUID.randomUUID();
        BookingResponse response = new BookingResponse(
                UUID.randomUUID(),
                reference,
                "CONFIRMED",
                Instant.now()
        );

        assertDoesNotThrow(() -> validator.validateBookingResponse(reference, response));
    }

    @Test
    void shouldThrowWhenBookingResponseIsNull() {
        UUID reference = UUID.randomUUID();
        IllegalStateException ex = assertThrows(
                IllegalStateException.class,
                () -> validator.validateBookingResponse(reference, null)
        );
        assertTrue(ex.getMessage().contains("no response"));
    }

    @Test
    void shouldThrowWhenBookingResponseIdIsNull() {
        UUID reference = UUID.randomUUID();
        BookingResponse response = new BookingResponse(
                null,
                reference,
                "CONFIRMED",
                Instant.now()
        );

        IllegalStateException ex = assertThrows(
                IllegalStateException.class,
                () -> validator.validateBookingResponse(reference, response)
        );
        assertTrue(ex.getMessage().contains("id is missing"));
    }

    @Test
    void shouldThrowWhenBookingReferenceDoesNotMatch() {
        UUID reference = UUID.randomUUID();
        UUID otherReference = UUID.randomUUID();
        BookingResponse response = new BookingResponse(
                UUID.randomUUID(),
                otherReference,
                "CONFIRMED",
                Instant.now()
        );

        IllegalStateException ex = assertThrows(
                IllegalStateException.class,
                () -> validator.validateBookingResponse(reference, response)
        );
        assertTrue(ex.getMessage().contains("does not match step"));
    }

    @Test
    void shouldThrowWhenBookingStatusIsNotConfirmed() {
        UUID reference = UUID.randomUUID();
        BookingResponse response = new BookingResponse(
                UUID.randomUUID(),
                reference,
                "PENDING",
                Instant.now()
        );

        IllegalStateException ex = assertThrows(
                IllegalStateException.class,
                () -> validator.validateBookingResponse(reference, response)
        );
        assertTrue(ex.getMessage().contains("Unexpected external booking status"));
    }

    @Test
    void shouldValidateSuccessfulCancellationResponse() {
        UUID externalBookingId = UUID.randomUUID();
        CancelBookingResponse response = new CancelBookingResponse(
                externalBookingId,
                "CANCELLED",
                Instant.now()
        );

        assertDoesNotThrow(() -> validator.validateCancellationResponse(externalBookingId, response));
    }

    @Test
    void shouldThrowWhenCancellationResponseIsNull() {
        UUID externalBookingId = UUID.randomUUID();
        IllegalStateException ex = assertThrows(
                IllegalStateException.class,
                () -> validator.validateCancellationResponse(externalBookingId, null)
        );
        assertTrue(ex.getMessage().contains("no response"));
    }

    @Test
    void shouldThrowWhenCancellationIdDoesNotMatch() {
        UUID externalBookingId = UUID.randomUUID();
        UUID otherBookingId = UUID.randomUUID();
        CancelBookingResponse response = new CancelBookingResponse(
                otherBookingId,
                "CANCELLED",
                Instant.now()
        );

        IllegalStateException ex = assertThrows(
                IllegalStateException.class,
                () -> validator.validateCancellationResponse(externalBookingId, response)
        );
        assertTrue(ex.getMessage().contains("does not match external booking"));
    }

    @Test
    void shouldThrowWhenCancellationStatusIsNotCancelled() {
        UUID externalBookingId = UUID.randomUUID();
        CancelBookingResponse response = new CancelBookingResponse(
                externalBookingId,
                "CONFIRMED",
                Instant.now()
        );

        IllegalStateException ex = assertThrows(
                IllegalStateException.class,
                () -> validator.validateCancellationResponse(externalBookingId, response)
        );
        assertTrue(ex.getMessage().contains("Unexpected external cancellation status"));
    }
}
