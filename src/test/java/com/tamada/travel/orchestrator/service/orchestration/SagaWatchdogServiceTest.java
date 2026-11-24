package com.tamada.travel.orchestrator.service.orchestration;

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
class SagaWatchdogServiceTest {

    @Mock
    private CompositeOrderRepository repository;

    @Mock
    private CompositeOrderService orderService;

    private final Instant fixedNow = Instant.parse("2026-11-21T12:00:00Z");
    private Clock clock;
    private SagaWatchdogService watchdogService;

    @BeforeEach
    void setUp() {
        clock = Clock.fixed(fixedNow, ZoneId.of("UTC"));
        watchdogService = new SagaWatchdogService(repository, orderService, clock);
    }

    @Test
    void shouldPerformMaintenanceOnBothProcessingAndCancellingOrders() {
        CompositeOrder processingOrder = CompositeOrder.create();
        UUID processingId = UUID.randomUUID();
        ReflectionTestUtils.setField(processingOrder, "id", processingId);
        processingOrder.addBookingStep(BookingType.FLIGHT, UUID.randomUUID());

        CompositeOrder cancellingOrder = CompositeOrder.create();
        UUID cancellingId = UUID.randomUUID();
        ReflectionTestUtils.setField(cancellingOrder, "id", cancellingId);
        cancellingOrder.addBookingStep(BookingType.HOTEL, UUID.randomUUID());

        Instant expectedCutoff = fixedNow.minus(Duration.ofMinutes(2));

        when(repository.findByStatusAndUpdatedAtBeforeOrderByUpdatedAtAsc(eq(OrderStatus.PROCESSING), eq(expectedCutoff)))
                .thenReturn(List.of(processingOrder));
        when(repository.findByStatusAndUpdatedAtBeforeOrderByUpdatedAtAsc(eq(OrderStatus.CANCELLING), eq(expectedCutoff)))
                .thenReturn(List.of(cancellingOrder));

        int totalHandled = watchdogService.performMaintenance(Duration.ofMinutes(2));

        assertEquals(2, totalHandled);
        verify(orderService).recoverOrder(processingId);
        verify(orderService).compensateOrder(cancellingId);
    }

    @Test
    void shouldReturnZeroWhenNoStuckOrdersFound() {
        Instant expectedCutoff = fixedNow.minus(Duration.ofMinutes(2));

        when(repository.findByStatusAndUpdatedAtBeforeOrderByUpdatedAtAsc(eq(OrderStatus.PROCESSING), eq(expectedCutoff)))
                .thenReturn(List.of());
        when(repository.findByStatusAndUpdatedAtBeforeOrderByUpdatedAtAsc(eq(OrderStatus.CANCELLING), eq(expectedCutoff)))
                .thenReturn(List.of());

        int totalHandled = watchdogService.performMaintenance(Duration.ofMinutes(2));

        assertEquals(0, totalHandled);
        verify(orderService, never()).recoverOrder(any());
        verify(orderService, never()).compensateOrder(any());
    }
}
