package com.tamada.travel.orchestrator.model;

import com.tamada.travel.orchestrator.exception.InvalidStateTransitionException;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

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
    void shouldCompensateConfirmedBookingSteps() {
        CompositeOrder order = CompositeOrder.create();
        order.addBookingStep(BookingType.FLIGHT);
        order.addBookingStep(BookingType.HOTEL);
        order.startProcessing();
        BookingStep flightStep = order.startNextBookingStep().orElseThrow();
        flightStep.confirm("flight-123");
        BookingStep hotelStep = order.startNextBookingStep().orElseThrow();
        hotelStep.confirm("hotel-123");
        order.fail();

        order.compensateConfirmedBookings();

        assertEquals(BookingStatus.CANCELLED, flightStep.getStatus());
        assertEquals(BookingStatus.CANCELLED, hotelStep.getStatus());
    }

    @Test
    void shouldKeepFailedStepAfterCompensation() {
        CompositeOrder order = CompositeOrder.create();
        order.addBookingStep(BookingType.FLIGHT);
        order.startProcessing();
        BookingStep failedStep = order.startNextBookingStep().orElseThrow();
        order.failCurrentBookingStep();

        order.compensateConfirmedBookings();

        assertEquals(BookingStatus.FAILED, failedStep.getStatus());
    }

    @Test
    void shouldKeepPendingStepsAfterCompensation() {
        CompositeOrder order = CompositeOrder.create();
        order.addBookingStep(BookingType.FLIGHT);
        order.addBookingStep(BookingType.HOTEL);
        order.startProcessing();
        order.startNextBookingStep().orElseThrow();
        BookingStep pendingStep = order.getBookingSteps().get(1);
        order.failCurrentBookingStep();

        order.compensateConfirmedBookings();

        assertEquals(BookingStatus.PENDING, pendingStep.getStatus());
    }

    @Test
    void shouldCancelOrderAfterCompensation() {
        CompositeOrder order = CompositeOrder.create();
        order.addBookingStep(BookingType.FLIGHT);
        order.addBookingStep(BookingType.HOTEL);
        order.startProcessing();
        BookingStep confirmedStep = order.startNextBookingStep().orElseThrow();
        confirmedStep.confirm("flight-123");
        order.startNextBookingStep().orElseThrow();
        order.failCurrentBookingStep();

        order.compensateConfirmedBookings();

        assertEquals(OrderStatus.CANCELLED, order.getStatus());
    }

    @Test
    void shouldCompensateConfirmedStepsInReverseOrder() {
        CompositeOrder order = CompositeOrder.create();
        List<BookingType> compensationOrder = new ArrayList<>();
        BookingStep flightStep = new RecordingBookingStep(
                order, BookingType.FLIGHT, compensationOrder);
        BookingStep hotelStep = new RecordingBookingStep(
                order, BookingType.HOTEL, compensationOrder);
        order.getBookingSteps().add(flightStep);
        order.getBookingSteps().add(hotelStep);
        order.startProcessing();
        flightStep.startProcessing();
        flightStep.confirm("flight-123");
        hotelStep.startProcessing();
        hotelStep.confirm("hotel-123");
        order.fail();

        order.compensateConfirmedBookings();

        assertEquals(
                List.of(BookingType.HOTEL, BookingType.FLIGHT),
                compensationOrder);
    }

    @Test
    void shouldCompleteCompensationWhenNoConfirmedSteps() {
        CompositeOrder order = CompositeOrder.create();
        order.addBookingStep(BookingType.FLIGHT);
        order.startProcessing();
        BookingStep failedStep = order.startNextBookingStep().orElseThrow();
        order.failCurrentBookingStep();

        order.compensateConfirmedBookings();

        assertEquals(BookingStatus.FAILED, failedStep.getStatus());
        assertEquals(OrderStatus.CANCELLED, order.getStatus());
    }

    @Test
    void shouldRejectCompensationWhenOrderIsNotFailed() {
        CompositeOrder order = CompositeOrder.create();
        order.addBookingStep(BookingType.FLIGHT);

        assertThrows(
                InvalidStateTransitionException.class,
                order::compensateConfirmedBookings);
        assertEquals(OrderStatus.NEW, order.getStatus());
        assertEquals(BookingStatus.PENDING, order.getBookingSteps().getFirst().getStatus());
    }

    @Test
    void shouldCancelProcessingOrder() {
        CompositeOrder order = CompositeOrder.create();
        order.addBookingStep(BookingType.FLIGHT);
        order.startProcessing();

        order.cancelManually();

        assertEquals(OrderStatus.CANCELLED, order.getStatus());
    }

    @Test
    void shouldCancelCompletedOrder() {
        CompositeOrder order = CompositeOrder.create();
        order.addBookingStep(BookingType.FLIGHT);
        order.startProcessing();
        BookingStep step = order.startNextBookingStep().orElseThrow();
        step.confirm("flight-123");
        order.complete();

        order.cancelManually();

        assertEquals(OrderStatus.CANCELLED, order.getStatus());
    }

    @Test
    void shouldCancelConfirmedSteps() {
        CompositeOrder order = CompositeOrder.create();
        order.addBookingStep(BookingType.FLIGHT);
        order.addBookingStep(BookingType.HOTEL);
        order.startProcessing();
        BookingStep flightStep = order.startNextBookingStep().orElseThrow();
        flightStep.confirm("flight-123");
        BookingStep hotelStep = order.startNextBookingStep().orElseThrow();
        hotelStep.confirm("hotel-123");

        order.cancelManually();

        assertEquals(BookingStatus.CANCELLED, flightStep.getStatus());
        assertEquals(BookingStatus.CANCELLED, hotelStep.getStatus());
    }

    @Test
    void shouldCancelConfirmedStepsInReverseOrder() {
        CompositeOrder order = CompositeOrder.create();
        List<BookingType> cancellationOrder = new ArrayList<>();
        BookingStep flightStep = new RecordingBookingStep(
                order, BookingType.FLIGHT, cancellationOrder);
        BookingStep hotelStep = new RecordingBookingStep(
                order, BookingType.HOTEL, cancellationOrder);
        order.getBookingSteps().add(flightStep);
        order.getBookingSteps().add(hotelStep);
        order.startProcessing();
        flightStep.startProcessing();
        flightStep.confirm("flight-123");
        hotelStep.startProcessing();
        hotelStep.confirm("hotel-123");

        order.cancelManually();

        assertEquals(
                List.of(BookingType.HOTEL, BookingType.FLIGHT),
                cancellationOrder);
    }

    @Test
    void shouldKeepPendingStepsWhenOrderCancelled() {
        CompositeOrder order = CompositeOrder.create();
        order.addBookingStep(BookingType.FLIGHT);
        order.addBookingStep(BookingType.HOTEL);
        order.startProcessing();
        BookingStep confirmedStep = order.startNextBookingStep().orElseThrow();
        confirmedStep.confirm("flight-123");
        BookingStep pendingStep = order.getBookingSteps().get(1);

        order.cancelManually();

        assertEquals(BookingStatus.CANCELLED, confirmedStep.getStatus());
        assertEquals(BookingStatus.PENDING, pendingStep.getStatus());
    }

    @Test
    void shouldKeepProcessingStepWhenOrderCancelled() {
        CompositeOrder order = CompositeOrder.create();
        order.addBookingStep(BookingType.FLIGHT);
        order.startProcessing();
        BookingStep processingStep = order.startNextBookingStep().orElseThrow();

        order.cancelManually();

        assertEquals(BookingStatus.PROCESSING, processingStep.getStatus());
        assertEquals(OrderStatus.CANCELLED, order.getStatus());
    }

    @Test
    void shouldRejectCancellationFromNewStatus() {
        CompositeOrder order = CompositeOrder.create();

        assertThrows(
                InvalidStateTransitionException.class,
                order::cancelManually);
        assertEquals(OrderStatus.NEW, order.getStatus());
    }

    @Test
    void shouldRejectCancellationFromCancellingStatus() {
        CompositeOrder order = CompositeOrder.create();
        order.startProcessing();
        order.startCancelling();

        assertThrows(
                InvalidStateTransitionException.class,
                order::cancelManually);
        assertEquals(OrderStatus.CANCELLING, order.getStatus());
    }

    @Test
    void shouldRejectCancellationFromCancelledStatus() {
        CompositeOrder order = CompositeOrder.create();
        order.startProcessing();
        order.cancelManually();

        assertThrows(
                InvalidStateTransitionException.class,
                order::cancelManually);
        assertEquals(OrderStatus.CANCELLED, order.getStatus());
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
    void shouldRejectSecondProcessingStart() {
        CompositeOrder order = CompositeOrder.create();

        order.startProcessing();

        assertThrows(
                InvalidStateTransitionException.class,
                order::startProcessing);
    }

    private static class RecordingBookingStep extends BookingStep {

        private final List<BookingType> compensationOrder;

        private RecordingBookingStep(
                CompositeOrder order,
                BookingType type,
                List<BookingType> compensationOrder) {
            super(order, type);
            this.compensationOrder = compensationOrder;
        }

        @Override
        public void startCancelling() {
            compensationOrder.add(getType());
            super.startCancelling();
        }
    }
}
