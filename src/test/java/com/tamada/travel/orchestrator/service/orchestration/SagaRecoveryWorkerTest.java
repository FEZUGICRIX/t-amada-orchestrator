package com.tamada.travel.orchestrator.service.orchestration;

import com.tamada.travel.orchestrator.config.SagaRecoveryProperties;
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
class SagaRecoveryWorkerTest {

    @Mock
    private CompositeOrderRepository repository;

    @Mock
    private CompositeOrderService orderService;

    private final Instant fixedNow = Instant.parse("2026-11-19T12:00:00Z");
    private Clock clock;
    private SagaRecoveryProperties properties;
    private SagaRecoveryWorker worker;

    @BeforeEach
    void setUp() {
        clock = Clock.fixed(fixedNow, ZoneId.of("UTC"));
        properties = new SagaRecoveryProperties(true, Duration.ofMinutes(2), Duration.ofSeconds(60), Duration.ofSeconds(60));
        worker = new SagaRecoveryWorker(repository, orderService, properties, clock);
    }

    @Test
    void shouldRecoverStuckOrdersWhenThresholdExceeded() {
        CompositeOrder stuckOrder = CompositeOrder.create();
        UUID orderId = UUID.randomUUID();
        ReflectionTestUtils.setField(stuckOrder, "id", orderId);
        stuckOrder.addBookingStep(BookingType.FLIGHT, UUID.randomUUID());

        Instant expectedCutoff = fixedNow.minus(Duration.ofMinutes(2));

        when(repository.findByStatusAndUpdatedAtBeforeOrderByUpdatedAtAsc(eq(OrderStatus.PROCESSING), eq(expectedCutoff)))
                .thenReturn(List.of(stuckOrder));

        int recoveredCount = worker.recoverStuckOrders();

        assertEquals(1, recoveredCount);
        verify(repository).findByStatusAndUpdatedAtBeforeOrderByUpdatedAtAsc(OrderStatus.PROCESSING, expectedCutoff);
        verify(orderService).recoverOrder(orderId);
    }

    @Test
    void shouldReturnZeroWhenNoStuckOrders() {
        Instant expectedCutoff = fixedNow.minus(Duration.ofMinutes(2));

        when(repository.findByStatusAndUpdatedAtBeforeOrderByUpdatedAtAsc(eq(OrderStatus.PROCESSING), eq(expectedCutoff)))
                .thenReturn(List.of());

        int recoveredCount = worker.recoverStuckOrders();

        assertEquals(0, recoveredCount);
        verify(orderService, never()).recoverOrder(any());
    }

    @Test
    void shouldHandleFailureInOneOrderAndContinueWithOthers() {
        CompositeOrder order1 = CompositeOrder.create();
        UUID id1 = UUID.randomUUID();
        ReflectionTestUtils.setField(order1, "id", id1);

        CompositeOrder order2 = CompositeOrder.create();
        UUID id2 = UUID.randomUUID();
        ReflectionTestUtils.setField(order2, "id", id2);

        when(repository.findByStatusAndUpdatedAtBeforeOrderByUpdatedAtAsc(eq(OrderStatus.PROCESSING), any(Instant.class)))
                .thenReturn(List.of(order1, order2));

        doThrow(new RuntimeException("Supplier unavailable")).when(orderService).recoverOrder(id1);
        when(orderService.recoverOrder(id2)).thenReturn(null);

        int recoveredCount = worker.recoverStuckOrders();

        assertEquals(1, recoveredCount);
        verify(orderService).recoverOrder(id1);
        verify(orderService).recoverOrder(id2);
    }

    @Test
    void shouldNotRecoverWhenDisabled() {
        SagaRecoveryProperties disabledProps = new SagaRecoveryProperties(
                false, Duration.ofMinutes(2), Duration.ofSeconds(60), Duration.ofSeconds(60)
        );
        SagaRecoveryWorker disabledWorker = new SagaRecoveryWorker(repository, orderService, disabledProps, clock);

        disabledWorker.onApplicationReady();
        disabledWorker.scheduledRecovery();

        verify(repository, never()).findByStatusAndUpdatedAtBeforeOrderByUpdatedAtAsc(any(), any());
        verify(orderService, never()).recoverOrder(any());
    }

    @Test
    void shouldTriggerRecoveryOnApplicationReady() {
        when(repository.findByStatusAndUpdatedAtBeforeOrderByUpdatedAtAsc(any(), any()))
                .thenReturn(List.of());

        worker.onApplicationReady();

        verify(repository).findByStatusAndUpdatedAtBeforeOrderByUpdatedAtAsc(eq(OrderStatus.PROCESSING), any(Instant.class));
    }

    @Test
    void shouldTriggerRecoveryOnSchedule() {
        when(repository.findByStatusAndUpdatedAtBeforeOrderByUpdatedAtAsc(any(), any()))
                .thenReturn(List.of());

        worker.scheduledRecovery();

        verify(repository).findByStatusAndUpdatedAtBeforeOrderByUpdatedAtAsc(eq(OrderStatus.PROCESSING), any(Instant.class));
    }
}
