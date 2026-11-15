package com.tamada.travel.orchestrator.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.tamada.travel.orchestrator.model.BookingType;
import com.tamada.travel.orchestrator.model.CompositeOrder;
import com.tamada.travel.orchestrator.model.OutboxMessage;
import com.tamada.travel.orchestrator.model.OutboxStatus;
import com.tamada.travel.orchestrator.repository.OutboxRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OutboxServiceLifecycleTest {

    @Mock
    private OutboxRepository outboxRepository;

    private OutboxService outboxService;

    @BeforeEach
    void setUp() {
        ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        outboxService = new OutboxService(outboxRepository, objectMapper);
    }

    @Test
    void shouldEmitEventsForAllLifecycleStages() {
        UUID orderId = UUID.randomUUID();
        CompositeOrder order = CompositeOrder.create();
        ReflectionTestUtils.setField(order, "id", orderId);
        order.addBookingStep(BookingType.FLIGHT, UUID.randomUUID(), new BigDecimal("10000.00"));

        when(outboxRepository.save(any(OutboxMessage.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        OutboxMessage createdMsg = outboxService.saveOrderCreatedEvent(order);
        assertEquals(OutboxService.EVENT_ORDER_CREATED, createdMsg.getEventType());
        assertEquals(OutboxStatus.PENDING, createdMsg.getStatus());
        assertEquals(orderId.toString(), createdMsg.getAggregateId());

        OutboxMessage processingMsg = outboxService.saveOrderProcessingEvent(order);
        assertEquals(OutboxService.EVENT_ORDER_PROCESSING, processingMsg.getEventType());

        OutboxMessage completedMsg = outboxService.saveOrderCompletedEvent(order);
        assertEquals(OutboxService.EVENT_ORDER_COMPLETED, completedMsg.getEventType());

        OutboxMessage cancelledMsg = outboxService.saveOrderCancelledEvent(order);
        assertEquals(OutboxService.EVENT_ORDER_CANCELLED, cancelledMsg.getEventType());

        OutboxMessage failedMsg = outboxService.saveOrderFailedEvent(order);
        assertEquals(OutboxService.EVENT_ORDER_FAILED, failedMsg.getEventType());
    }
}
