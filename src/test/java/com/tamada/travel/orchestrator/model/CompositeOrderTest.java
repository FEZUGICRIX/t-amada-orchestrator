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
    void shouldCompleteOrderWhenAllBookingStepsAreConfirmed() {
        CompositeOrder order = CompositeOrder.create();
        order.addBookingStep(BookingType.FLIGHT);
        order.addBookingStep(BookingType.HOTEL);
        order.startProcessing();

        BookingStep firstStep = order.startNextBookingStep().orElseThrow();
        firstStep.confirm("flight-123");
        BookingStep secondStep = order.startNextBookingStep().orElseThrow();
        secondStep.confirm("hotel-123");

        order.complete();

        assertEquals(OrderStatus.COMPLETED, order.getStatus());
    }

    @Test
    void shouldRejectCompleteWhenBookingStepIsPending() {
        CompositeOrder order = CompositeOrder.create();
        order.addBookingStep(BookingType.FLIGHT);
        order.startProcessing();

        assertThrows(
                InvalidStateTransitionException.class,
                order::complete);
        assertEquals(OrderStatus.PROCESSING, order.getStatus());
    }

    @Test
    void shouldRejectCompleteWhenBookingStepIsProcessing() {
        CompositeOrder order = CompositeOrder.create();
        order.addBookingStep(BookingType.FLIGHT);
        order.startProcessing();
        order.startNextBookingStep().orElseThrow();

        assertThrows(
                InvalidStateTransitionException.class,
                order::complete);
        assertEquals(OrderStatus.PROCESSING, order.getStatus());
    }

    @Test
    void shouldFailCurrentBookingStepAndOrder() {
        CompositeOrder order = CompositeOrder.create();
        order.addBookingStep(BookingType.FLIGHT);
        order.startProcessing();
        BookingStep currentStep = order.startNextBookingStep().orElseThrow();

        order.failCurrentBookingStep();

        assertEquals(BookingStatus.FAILED, currentStep.getStatus());
        assertEquals(OrderStatus.FAILED, order.getStatus());
    }

    @Test
    void shouldKeepConfirmedStepsWhenCurrentStepFails() {
        CompositeOrder order = CompositeOrder.create();
        order.addBookingStep(BookingType.FLIGHT);
        order.addBookingStep(BookingType.HOTEL);
        order.startProcessing();
        BookingStep confirmedStep = order.startNextBookingStep().orElseThrow();
        confirmedStep.confirm("flight-123");
        order.startNextBookingStep().orElseThrow();

        order.failCurrentBookingStep();

        assertEquals(BookingStatus.CONFIRMED, confirmedStep.getStatus());
    }

    @Test
    void shouldKeepPendingStepsWhenCurrentStepFails() {
        CompositeOrder order = CompositeOrder.create();
        order.addBookingStep(BookingType.FLIGHT);
        order.addBookingStep(BookingType.HOTEL);
        order.startProcessing();
        order.startNextBookingStep().orElseThrow();
        BookingStep pendingStep = order.getBookingSteps().get(1);

        order.failCurrentBookingStep();

        assertEquals(BookingStatus.PENDING, pendingStep.getStatus());
    }

    @Test
    void shouldNotStartNextStepAfterOrderFailed() {
        CompositeOrder order = CompositeOrder.create();
        order.addBookingStep(BookingType.FLIGHT);
        order.addBookingStep(BookingType.HOTEL);
        order.startProcessing();
        order.startNextBookingStep().orElseThrow();

        order.failCurrentBookingStep();

        assertTrue(order.startNextBookingStep().isEmpty());
        assertEquals(BookingStatus.PENDING, order.getBookingSteps().get(1).getStatus());
    }

    @Test
    void shouldRejectStepFailureWhenNoStepIsProcessing() {
        CompositeOrder order = CompositeOrder.create();
        order.addBookingStep(BookingType.FLIGHT);
        order.startProcessing();

        assertThrows(
                InvalidStateTransitionException.class,
                order::failCurrentBookingStep);
        assertEquals(OrderStatus.PROCESSING, order.getStatus());
        assertEquals(BookingStatus.PENDING, order.getBookingSteps().getFirst().getStatus());
    }

    @Test
    void shouldRejectStepFailureWhenOrderIsNotProcessing() {
        CompositeOrder order = CompositeOrder.create();
        order.addBookingStep(BookingType.FLIGHT);

        assertThrows(
                InvalidStateTransitionException.class,
                order::failCurrentBookingStep);
        assertEquals(OrderStatus.NEW, order.getStatus());
        assertEquals(BookingStatus.PENDING, order.getBookingSteps().getFirst().getStatus());
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
                order::complete);
    }

    @Test
    void shouldRejectCancellationFromProcessingStatus() {
        CompositeOrder order = CompositeOrder.create();

        order.startProcessing();

        assertThrows(
                InvalidStateTransitionException.class,
                order::startCancelling);
    }

    @Test
    void shouldRejectSecondProcessingStart() {
        CompositeOrder order = CompositeOrder.create();

        order.startProcessing();

        assertThrows(
                InvalidStateTransitionException.class,
                order::startProcessing);
    }
}
