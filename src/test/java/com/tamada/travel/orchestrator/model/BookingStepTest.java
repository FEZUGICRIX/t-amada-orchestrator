package com.tamada.travel.orchestrator.model;

import com.tamada.travel.orchestrator.exception.InvalidBookingDataException;
import com.tamada.travel.orchestrator.exception.InvalidStateTransitionException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class BookingStepTest {

    @Test
    void shouldCreateBookingStepWithPendingStatus() {
        CompositeOrder order = CompositeOrder.create();

        BookingStep step = new BookingStep(order, BookingType.FLIGHT);

        assertEquals(BookingType.FLIGHT, step.getType());
        assertEquals(BookingStatus.PENDING, step.getStatus());
        assertSame(order, step.getCompositeOrder());
    }

    @Test
    void shouldStartProcessing() {
        BookingStep step = createStep();

        step.startProcessing();

        assertEquals(BookingStatus.PROCESSING, step.getStatus());
    }

    @Test
    void shouldConfirmBooking() {
        BookingStep step = createStep();

        step.startProcessing();
        step.confirm("flight-123");

        assertEquals(BookingStatus.CONFIRMED, step.getStatus());
        assertEquals("flight-123", step.getExternalId());
    }

    @Test
    void shouldFailBooking() {
        BookingStep step = createStep();

        step.startProcessing();
        step.fail();

        assertEquals(BookingStatus.FAILED, step.getStatus());
    }

    @Test
    void shouldCancelConfirmedBooking() {
        BookingStep step = createStep();

        step.startProcessing();
        step.confirm("flight-123");
        step.startCancelling();
        step.cancel();

        assertEquals(BookingStatus.CANCELLED, step.getStatus());
    }

    @Test
    void shouldRejectConfirmFromPendingStatus() {
        BookingStep step = createStep();

        assertThrows(
                InvalidStateTransitionException.class,
                () -> step.confirm("flight-123")
        );
    }

    @Test
    void shouldRejectFailFromPendingStatus() {
        BookingStep step = createStep();

        assertThrows(
                InvalidStateTransitionException.class,
                step::fail
        );
    }

    @Test
    void shouldRejectCancellationFromPendingStatus() {
        BookingStep step = createStep();

        assertThrows(
                InvalidStateTransitionException.class,
                step::startCancelling
        );
    }

    @Test
    void shouldRejectEmptyExternalId() {
        BookingStep step = createStep();

        step.startProcessing();

        assertThrows(
                InvalidBookingDataException.class,
                () -> step.confirm("")
        );
    }

    @Test
    void shouldRejectNullExternalId() {
        BookingStep step = createStep();

        step.startProcessing();

        assertThrows(
                InvalidBookingDataException.class,
                () -> step.confirm(null)
        );
    }

    private BookingStep createStep() {
        return new BookingStep(
                CompositeOrder.create(),
                BookingType.FLIGHT
        );
    }
}