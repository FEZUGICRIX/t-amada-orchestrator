package com.tamada.travel.orchestrator.model;

import com.tamada.travel.orchestrator.exception.InvalidStateTransitionException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CompositeOrderTest {

    @Test
    void shouldCreateOrderWithNewStatus() {
        CompositeOrder order = CompositeOrder.create();

        assertEquals(OrderStatus.NEW, order.getStatus());
        assertTrue(order.getBookingSteps().isEmpty());
    }

    @Test
    void shouldAddBookingStep() {
        CompositeOrder order = CompositeOrder.create();

        order.addBookingStep(BookingType.HOTEL);

        assertEquals(1, order.getBookingSteps().size());

        BookingStep step = order.getBookingSteps().getFirst();

        assertEquals(BookingType.HOTEL, step.getType());
        assertEquals(BookingStatus.PENDING, step.getStatus());
        assertSame(order, step.getCompositeOrder());
    }

    @Test
    void shouldAddSeveralBookingSteps() {
        CompositeOrder order = CompositeOrder.create();

        order.addBookingStep(BookingType.FLIGHT);
        order.addBookingStep(BookingType.HOTEL);
        order.addBookingStep(BookingType.INSURANCE);

        assertEquals(3, order.getBookingSteps().size());
    }

    @Test
    void shouldCompleteOrder() {
        CompositeOrder order = CompositeOrder.create();

        order.startProcessing();
        order.complete();

        assertEquals(OrderStatus.COMPLETED, order.getStatus());
    }

    @Test
    void shouldCancelFailedOrder() {
        CompositeOrder order = CompositeOrder.create();

        order.startProcessing();
        order.fail();
        order.startCancelling();
        order.cancel();

        assertEquals(OrderStatus.CANCELLED, order.getStatus());
    }

    @Test
    void shouldRejectCompleteFromNewStatus() {
        CompositeOrder order = CompositeOrder.create();

        assertThrows(
                InvalidStateTransitionException.class,
                order::complete
        );
    }

    @Test
    void shouldRejectCancellationFromProcessingStatus() {
        CompositeOrder order = CompositeOrder.create();

        order.startProcessing();

        assertThrows(
                InvalidStateTransitionException.class,
                order::startCancelling
        );
    }

    @Test
    void shouldRejectSecondProcessingStart() {
        CompositeOrder order = CompositeOrder.create();

        order.startProcessing();

        assertThrows(
                InvalidStateTransitionException.class,
                order::startProcessing
        );
    }
}