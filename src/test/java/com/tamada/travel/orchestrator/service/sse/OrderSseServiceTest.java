package com.tamada.travel.orchestrator.service.sse;

import com.tamada.travel.orchestrator.dto.error.StepErrorDetailsResponse;
import com.tamada.travel.orchestrator.model.BookingStatus;
import com.tamada.travel.orchestrator.model.BookingType;
import com.tamada.travel.orchestrator.model.CompositeOrder;
import com.tamada.travel.orchestrator.model.OrderStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class OrderSseServiceTest {

    private OrderSseService sseService;

    @BeforeEach
    void setUp() {
        sseService = new OrderSseService();
    }

    @Test
    void shouldSubscribeToNewOrderAndReceiveEvents() {
        UUID orderId = UUID.randomUUID();
        CompositeOrder order = CompositeOrder.create();

        SseEmitter emitter = sseService.subscribe(orderId, order);
        assertNotNull(emitter);

        assertDoesNotThrow(() -> {
            sseService.publishOrderStatus(orderId, OrderStatus.PROCESSING);
            sseService.publishStepStatus(orderId, UUID.randomUUID(), BookingType.FLIGHT, BookingStatus.PROCESSING, null);
            sseService.publishStepStatus(orderId, UUID.randomUUID(), BookingType.FLIGHT, BookingStatus.CONFIRMED, "ext-1");
            sseService.publishOrderCompleted(orderId);
        });
    }

    @Test
    void shouldPublishStepFailureAndCancellationEvents() {
        UUID orderId = UUID.randomUUID();
        CompositeOrder order = CompositeOrder.create();
        UUID failedStepId = UUID.randomUUID();
        UUID compensatedStepId = UUID.randomUUID();

        SseEmitter emitter = sseService.subscribe(orderId, order);
        assertNotNull(emitter);

        StepErrorDetailsResponse details = new StepErrorDetailsResponse(
                409,
                "Conflict",
                "Resource is unavailable",
                "/api/v1/hotel/book",
                "2026-10-18T10:00:00Z"
        );

        assertDoesNotThrow(() -> {
            sseService.publishStepFailed(
                    orderId,
                    failedStepId,
                    BookingType.HOTEL,
                    "RESOURCE_UNAVAILABLE",
                    409,
                    "Resource is unavailable",
                    "Номер в отеле недоступен на выбранные даты.",
                    details
            );

            sseService.publishStepCancelled(
                    orderId,
                    compensatedStepId,
                    BookingType.FLIGHT,
                    BookingStatus.CANCELLED,
                    "Отменено: компенсация из-за сбоя бронирования отеля",
                    "Бронирование авиабилета отменено."
            );

            sseService.publishOrderCancelled(
                    orderId,
                    "Сбой бронирования отеля: Resource is unavailable",
                    "Заказ не может быть выполнен."
            );
        });
    }

    @Test
    void shouldCompleteImmediatelyIfOrderAlreadyCompletedOrCancelled() {
        UUID orderId = UUID.randomUUID();
        CompositeOrder completedOrder = CompositeOrder.create();
        completedOrder.addBookingStep(BookingType.FLIGHT, UUID.randomUUID());
        completedOrder.startProcessing();
        completedOrder.getBookingSteps().getFirst().startProcessing();
        completedOrder.getBookingSteps().getFirst().confirm("ext-1");
        completedOrder.complete();

        SseEmitter emitter = sseService.subscribe(orderId, completedOrder);
        assertNotNull(emitter);
    }
}
