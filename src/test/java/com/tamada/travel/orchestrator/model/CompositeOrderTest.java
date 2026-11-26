package com.tamada.travel.orchestrator.model;

import com.tamada.travel.orchestrator.dto.HotelRoomRequest;
import com.tamada.travel.orchestrator.exception.InvalidBookingDataException;
import com.tamada.travel.orchestrator.exception.InvalidStateTransitionException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class CompositeOrderTest {

    @Test
    void shouldCreateOrderWithNewStatus() {
        CompositeOrder order = CompositeOrder.create();

        assertEquals(OrderStatus.NEW, order.getStatus());
        assertTrue(order.getBookingSteps().isEmpty());
        assertEquals(new BigDecimal("0.00"), order.getBasePrice());
        assertEquals(new BigDecimal("0.00"), order.getServiceFee());
        assertEquals(new BigDecimal("0.00"), order.getTotalPrice());
    }

    @Test
    void shouldCalculatePricingAndServiceFeeWhenStepsAdded() {
        CompositeOrder order = CompositeOrder.create();

        order.addBookingStep(BookingType.FLIGHT, UUID.randomUUID(), new BigDecimal("10000.00"));

        // 10000 * 0.0067 = 67.00
        assertEquals(new BigDecimal("10000.00"), order.getBasePrice());
        assertEquals(new BigDecimal("67.00"), order.getServiceFee());
        assertEquals(new BigDecimal("10067.00"), order.getTotalPrice());

        // Add hotel: 5000.00 -> total base = 15000.00
        // 15000 * 0.0067 = 100.50 -> total price = 15100.50
        order.addBookingStep(BookingType.HOTEL, UUID.randomUUID(), new BigDecimal("5000.00"));
        assertEquals(new BigDecimal("15000.00"), order.getBasePrice());
        assertEquals(new BigDecimal("100.50"), order.getServiceFee());
        assertEquals(new BigDecimal("15100.50"), order.getTotalPrice());
    }

    @Test
    void shouldRoundServiceFeeHalfUp() {
        CompositeOrder order = CompositeOrder.create();

        // 1234.56 * 0.0067 = 8.271552 -> 8.27
        order.addBookingStep(BookingType.INSURANCE, UUID.randomUUID(), new BigDecimal("1234.56"));
        assertEquals(new BigDecimal("1234.56"), order.getBasePrice());
        assertEquals(new BigDecimal("8.27"), order.getServiceFee());
        assertEquals(new BigDecimal("1242.83"), order.getTotalPrice());
    }

    @Test
    void shouldAddBookingStep() {
        CompositeOrder order = CompositeOrder.create();
        UUID resourceId = UUID.randomUUID();

        order.addBookingStep(BookingType.HOTEL, resourceId);

        assertEquals(1, order.getBookingSteps().size());

        BookingStep step = order.getBookingSteps().getFirst();

        assertEquals(BookingType.HOTEL, step.getType());
        assertEquals(resourceId, step.getResourceId());
        assertEquals(BookingStatus.PENDING, step.getStatus());
        assertSame(order, step.getCompositeOrder());
    }

    @Test
    void shouldAddSeveralBookingSteps() {
        CompositeOrder order = CompositeOrder.create();

        order.addBookingStep(BookingType.FLIGHT, UUID.randomUUID());
        order.addBookingStep(BookingType.HOTEL, UUID.randomUUID());
        order.addBookingStep(BookingType.INSURANCE, UUID.randomUUID());

        assertEquals(3, order.getBookingSteps().size());
    }

    @Test
    void shouldCompleteOrderWhenAllBookingStepsAreConfirmed() {
        CompositeOrder order = CompositeOrder.create();
        order.addBookingStep(BookingType.FLIGHT, UUID.randomUUID());
        order.addBookingStep(BookingType.HOTEL, UUID.randomUUID());
        order.startProcessing();

        BookingStep firstStep = order.startNextBookingStep().orElseThrow();
        firstStep.confirm("flight-123");
        BookingStep secondStep = order.startNextBookingStep().orElseThrow();
        secondStep.confirm("hotel-123");

        order.complete();

        assertEquals(OrderStatus.COMPLETED, order.getStatus());
    }

    @Test
    void shouldRejectCompleteWhenStepsNotConfirmed() {
        CompositeOrder order = CompositeOrder.create();
        order.addBookingStep(BookingType.FLIGHT, UUID.randomUUID());
        order.addBookingStep(BookingType.HOTEL, UUID.randomUUID());
        order.startProcessing();

        BookingStep firstStep = order.startNextBookingStep().orElseThrow();
        firstStep.confirm("flight-123");

        assertThrows(
                InvalidStateTransitionException.class,
                order::complete
        );
        assertEquals(OrderStatus.PROCESSING, order.getStatus());
    }

    @Test
    void shouldFailOrderAndRecordMessage() {
        CompositeOrder order = CompositeOrder.create();
        order.startProcessing();

        order.fail("External service timeout", "Сервис временно недоступен");

        assertEquals(OrderStatus.FAILED, order.getStatus());
        assertEquals("External service timeout", order.getFailureReason());
        assertEquals("Сервис временно недоступен", order.getUserMessage());
    }

    @Test
    void shouldFailCurrentBookingStepAndFailOrder() {
        CompositeOrder order = CompositeOrder.create();
        order.addBookingStep(BookingType.FLIGHT, UUID.randomUUID());
        order.startProcessing();

        BookingStep step = order.startNextBookingStep().orElseThrow();
        order.failCurrentBookingStep("500", "Gateway Timeout", "Попробуйте позже", "{\"code\":500}");

        assertEquals(BookingStatus.FAILED, step.getStatus());
        assertEquals("500", step.getErrorCode());
        assertEquals("Gateway Timeout", step.getErrorMessage());
        assertEquals("Попробуйте позже", step.getUserMessage());
        assertEquals("{\"code\":500}", step.getErrorDetails());

        assertEquals(OrderStatus.FAILED, order.getStatus());
        assertEquals("Gateway Timeout", order.getFailureReason());
        assertEquals("Попробуйте позже", order.getUserMessage());
    }

    @Test
    void shouldRejectStepFailureWhenNoStepIsProcessing() {
        CompositeOrder order = CompositeOrder.create();
        order.addBookingStep(BookingType.FLIGHT, UUID.randomUUID());
        order.startProcessing();

        assertThrows(
                InvalidStateTransitionException.class,
                order::failCurrentBookingStep
        );
        assertEquals(OrderStatus.PROCESSING, order.getStatus());
        assertEquals(BookingStatus.PENDING, order.getBookingSteps().getFirst().getStatus());
    }

    @Test
    void shouldRejectStepFailureWhenOrderIsNotProcessing() {
        CompositeOrder order = CompositeOrder.create();
        order.addBookingStep(BookingType.FLIGHT, UUID.randomUUID());

        assertThrows(
                InvalidStateTransitionException.class,
                order::failCurrentBookingStep
        );
        assertEquals(OrderStatus.NEW, order.getStatus());
    }

    @Test
    void shouldStartCancellingFromProcessing() {
        CompositeOrder order = CompositeOrder.create();
        order.startProcessing();

        order.startCancelling();

        assertEquals(OrderStatus.CANCELLING, order.getStatus());
    }

    @Test
    void shouldStartCancellingFromCompleted() {
        CompositeOrder order = CompositeOrder.create();
        order.addBookingStep(BookingType.FLIGHT, UUID.randomUUID());
        order.startProcessing();
        BookingStep step = order.startNextBookingStep().orElseThrow();
        step.confirm("flight-123");
        order.complete();

        order.startCancelling();

        assertEquals(OrderStatus.CANCELLING, order.getStatus());
    }

    @Test
    void shouldStartCancellingFromFailed() {
        CompositeOrder order = CompositeOrder.create();
        order.startProcessing();
        order.fail();

        order.startCancelling();

        assertEquals(OrderStatus.CANCELLING, order.getStatus());
    }

    @Test
    void shouldCancelOrderFromCancelling() {
        CompositeOrder order = CompositeOrder.create();
        order.startProcessing();
        order.startCancelling();

        order.cancel("Отменено пользователем", "Заказ отменен.");

        assertEquals(OrderStatus.CANCELLED, order.getStatus());
        assertEquals("Отменено пользователем", order.getFailureReason());
        assertEquals("Заказ отменен.", order.getUserMessage());
    }

    @Test
    void shouldRejectCancellationFromNewStatus() {
        CompositeOrder order = CompositeOrder.create();

        assertThrows(
                InvalidStateTransitionException.class,
                order::startCancelling
        );
        assertThrows(
                InvalidStateTransitionException.class,
                order::cancel
        );
        assertEquals(OrderStatus.NEW, order.getStatus());
    }

    @Test
    void shouldRejectCancelWhenNotCancelling() {
        CompositeOrder order = CompositeOrder.create();
        order.startProcessing();

        assertThrows(
                InvalidStateTransitionException.class,
                order::cancel
        );
        assertEquals(OrderStatus.PROCESSING, order.getStatus());
    }

    @Test
    void shouldRejectStartProcessingFromProcessing() {
        CompositeOrder order = CompositeOrder.create();
        order.startProcessing();

        assertThrows(
                InvalidStateTransitionException.class,
                order::startProcessing
        );
    }

    @Test
    void shouldRejectStartProcessingFromCompletedWithoutPendingSteps() {
        CompositeOrder order = CompositeOrder.create();
        order.addBookingStep(BookingType.FLIGHT, UUID.randomUUID());
        order.startProcessing();
        BookingStep step = order.startNextBookingStep().orElseThrow();
        step.confirm("flight-123");
        order.complete();

        assertThrows(
                InvalidStateTransitionException.class,
                order::startProcessing
        );
    }

    @Test
    void shouldAllowStartProcessingFromCompletedWithPendingSteps() {
        CompositeOrder order = CompositeOrder.create();
        order.addBookingStep(BookingType.FLIGHT, UUID.randomUUID());
        order.startProcessing();
        BookingStep step1 = order.startNextBookingStep().orElseThrow();
        step1.confirm("flight-123");
        order.complete();

        // Добавили новый шаг PENDING
        order.addBookingStep(BookingType.HOTEL, UUID.randomUUID());

        order.startProcessing();
        assertEquals(OrderStatus.PROCESSING, order.getStatus());
    }

    @Test
    void shouldAllowStartProcessingFromFailed() {
        CompositeOrder order = CompositeOrder.create();
        order.startProcessing();
        order.fail();

        order.startProcessing();
        assertEquals(OrderStatus.PROCESSING, order.getStatus());
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
    void shouldResetProcessingStepsToPending() {
        CompositeOrder order = CompositeOrder.create();
        order.addBookingStep(BookingType.FLIGHT, UUID.randomUUID());
        order.addBookingStep(BookingType.HOTEL, UUID.randomUUID());
        order.startProcessing();

        BookingStep step1 = order.startNextBookingStep().orElseThrow();
        step1.confirm("ext-1");

        BookingStep step2 = order.startNextBookingStep().orElseThrow();
        assertEquals(BookingStatus.PROCESSING, step2.getStatus());

        order.resetProcessingStepsToPending();

        assertEquals(BookingStatus.CONFIRMED, step1.getStatus());
        assertEquals(BookingStatus.PENDING, step2.getStatus());
    }

    @Test
    void shouldRejectResetProcessingStepsWhenOrderNotProcessing() {
        CompositeOrder order = CompositeOrder.create();
        assertThrows(InvalidStateTransitionException.class, order::resetProcessingStepsToPending);
    }

    @Test
    void shouldAddPassengerAndCustomerContact() {
        CompositeOrder order = CompositeOrder.create();
        order.addPassenger(
                PassengerType.ADULT,
                "Иван",
                "Иванов",
                LocalDate.of(1990, 1, 1),
                "RU",
                "PASSPORT",
                "1234567890"
        );
        order.setCustomerContact("+79991234567", "ivan@example.com");

        assertEquals(1, order.getPassengers().size());
        Passenger passenger = order.getPassengers().getFirst();
        assertEquals("Иван", passenger.getFirstName());
        assertEquals("Иванов", passenger.getLastName());
        assertEquals(PassengerType.ADULT, passenger.getType());

        assertNotNull(order.getCustomerContact());
        assertEquals("+79991234567", order.getCustomerContact().getPhone());
        assertEquals("ivan@example.com", order.getCustomerContact().getEmail());
    }

    @Test
    void shouldCalculateSeatsCountExcludingInfants() {
        CompositeOrder order = CompositeOrder.create();
        assertEquals(0, order.calculateSeatsCount());

        order.addPassenger(PassengerType.ADULT, "Иван", "Иванов", LocalDate.of(1990, 1, 1), "RU", "PASSPORT", "1");
        order.addPassenger(PassengerType.ADULT, "Мария", "Иванова", LocalDate.of(1992, 2, 2), "RU", "PASSPORT", "2");
        order.addPassenger(PassengerType.CHILD, "Анна", "Иванова", LocalDate.of(2016, 3, 3), "RU", "BIRTH_CERT", "3");
        order.addPassenger(PassengerType.INFANT, "Михаил", "Иванов", LocalDate.of(2025, 4, 4), "RU", "BIRTH_CERT", "4");

        assertEquals(3, order.calculateSeatsCount());
    }

    @Test
    void shouldRecordOperationHistory() {
        CompositeOrder order = CompositeOrder.create();
        var history = order.recordOperation(
                null,
                null,
                "ORDER_CREATE",
                "NEW",
                201,
                null,
                null,
                "Заказ создан",
                null,
                null
        );

        assertNotNull(history);
        assertEquals(1, order.getOperationHistory().size());
        assertEquals("ORDER_CREATE", order.getOperationHistory().getFirst().getOperationType());
        assertEquals("NEW", order.getOperationHistory().getFirst().getStatus());
    }

    @Test
    void shouldReplaceBookingStepAndRecalculatePricing() {
        CompositeOrder order = CompositeOrder.create();
        BookingStep flight = order.addBookingStep(BookingType.FLIGHT, UUID.randomUUID(), new BigDecimal("10000.00"));
        BookingStep hotel = order.addBookingStep(BookingType.HOTEL, UUID.randomUUID(), new BigDecimal("5000.00"));

        UUID stepId = UUID.randomUUID();
        org.springframework.test.util.ReflectionTestUtils.setField(hotel, "id", stepId);

        UUID newHotelResourceId = UUID.randomUUID();
        HotelRoomRequest roomReq = new HotelRoomRequest("Люкс", "С завтраком", new BigDecimal("8000.00"), "Завтрак включен");

        order.replaceBookingStep(
                stepId,
                newHotelResourceId,
                new BigDecimal("8000.00"),
                List.of(roomReq)
        );

        assertEquals(newHotelResourceId, hotel.getResourceId());
        assertEquals(BookingStatus.PENDING, hotel.getStatus());
        assertEquals(new BigDecimal("8000.00"), hotel.getPrice());
        assertEquals(1, hotel.getRoomsCount());

        // Base price: 10000 + 8000 = 18000.00
        assertEquals(new BigDecimal("18000.00"), order.getBasePrice());
    }

    @Test
    void shouldRejectReplaceBookingStepWhenOrderCancelled() {
        CompositeOrder order = CompositeOrder.create();
        BookingStep hotel = order.addBookingStep(BookingType.HOTEL, UUID.randomUUID(), new BigDecimal("5000.00"));
        UUID stepId = UUID.randomUUID();
        org.springframework.test.util.ReflectionTestUtils.setField(hotel, "id", stepId);

        order.startProcessing();
        order.startCancelling();
        order.cancel();

        assertThrows(
                InvalidStateTransitionException.class,
                () -> order.replaceBookingStep(stepId, UUID.randomUUID(), new BigDecimal("6000.00"), null)
        );
    }
}
