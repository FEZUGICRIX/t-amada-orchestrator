package com.tamada.travel.orchestrator.service.orchestration;

import com.tamada.travel.orchestrator.config.SagaCompensationProperties;
import com.tamada.travel.orchestrator.model.BookingType;
import com.tamada.travel.orchestrator.model.CompositeOrder;
import com.tamada.travel.orchestrator.model.OrderStatus;
import com.tamada.travel.orchestrator.repository.CompositeOrderRepository;
import com.tamada.travel.orchestrator.service.CompositeOrderService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SagaCompensationWorkerTest {

    @Mock
    private CompositeOrderRepository repository;

    @Mock
    private CompositeOrderService orderService;

    private final Instant fixedNow = Instant.parse("2026-11-19T14:00:00Z");
    private Clock clock;
    private SagaCompensationProperties properties;
    private SagaCompensationWorker worker;

    @BeforeEach
    void setUp() {
        clock = Clock.fixed(fixedNow, ZoneId.of("UTC"));
        properties = new SagaCompensationProperties(true, Duration.ofMinutes(2), Duration.ofSeconds(60), Duration.ofSeconds(60));
        worker = new SagaCompensationWorker(repository, orderService, properties, clock);
    }

    @Test
    void shouldCompensateStuckCancellingOrdersWhenThresholdExceeded() {
        CompositeOrder stuckOrder = CompositeOrder.create();
        UUID orderId = UUID.randomUUID();
        ReflectionTestUtils.setField(stuckOrder, "id", orderId);
        stuckOrder.addBookingStep(BookingType.FLIGHT, UUID.randomUUID());

        Instant expectedCutoff = fixedNow.minus(Duration.ofMinutes(2));

        when(repository.findByStatusAndUpdatedAtBeforeOrderByUpdatedAtAsc(eq(OrderStatus.CANCELLING), eq(expectedCutoff)))
                .thenReturn(List.of(stuckOrder));

        int compensatedCount = worker.compensateStuckOrders();

        assertEquals(1, compensatedCount);
        verify(repository).findByStatusAndUpdatedAtBeforeOrderByUpdatedAtAsc(OrderStatus.CANCELLING, expectedCutoff);
        verify(orderService).compensateOrder(orderId);
    }

    @Test
    void shouldReturnZeroWhenNoStuckCancellingOrders() {
        Instant expectedCutoff = fixedNow.minus(Duration.ofMinutes(2));

        when(repository.findByStatusAndUpdatedAtBeforeOrderByUpdatedAtAsc(eq(OrderStatus.CANCELLING), eq(expectedCutoff)))
                .thenReturn(List.of());

        int compensatedCount = worker.compensateStuckOrders();

        assertEquals(0, compensatedCount);
        verify(orderService, never()).compensateOrder(any());
    }

    @Test
    void shouldHandleFailureInOneOrderAndContinueWithOthers() {
        CompositeOrder order1 = CompositeOrder.create();
        UUID id1 = UUID.randomUUID();
        ReflectionTestUtils.setField(order1, "id", id1);

        CompositeOrder order2 = CompositeOrder.create();
        UUID id2 = UUID.randomUUID();
        ReflectionTestUtils.setField(order2, "id", id2);

        when(repository.findByStatusAndUpdatedAtBeforeOrderByUpdatedAtAsc(eq(OrderStatus.CANCELLING), any(Instant.class)))
                .thenReturn(List.of(order1, order2));

        doThrow(new RuntimeException("External cancel failed")).when(orderService).compensateOrder(id1);
        when(orderService.compensateOrder(id2)).thenReturn(null);

        int compensatedCount = worker.compensateStuckOrders();

        assertEquals(1, compensatedCount);
        verify(orderService).compensateOrder(id1);
        verify(orderService).compensateOrder(id2);
    }

    @Test
    void shouldSkipWhenDisabled() {
        SagaCompensationProperties disabledProps = new SagaCompensationProperties(false, Duration.ofMinutes(2), Duration.ofSeconds(60), Duration.ofSeconds(60));
        SagaCompensationWorker disabledWorker = new SagaCompensationWorker(repository, orderService, disabledProps, clock);

        disabledWorker.onApplicationReady();
        disabledWorker.scheduledCompensation();

        verifyNoInteractions(repository);
        verifyNoInteractions(orderService);
    }
}
