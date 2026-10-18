package com.tamada.travel.orchestrator.service;

import com.tamada.travel.orchestrator.dto.CreateBookingStepRequest;
import com.tamada.travel.orchestrator.dto.CreateOrderRequest;
import com.tamada.travel.orchestrator.dto.OrderResponse;
import com.tamada.travel.orchestrator.exception.InvalidBookingDataException;
import com.tamada.travel.orchestrator.exception.OrderNotFoundException;
import com.tamada.travel.orchestrator.model.BookingType;
import com.tamada.travel.orchestrator.model.CompositeOrder;
import com.tamada.travel.orchestrator.model.OrderStatus;
import com.tamada.travel.orchestrator.repository.CompositeOrderRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CompositeOrderServiceTest {

    @Mock
    private CompositeOrderRepository repository;

    @Mock
    private BookingOrchestrationService orchestrationService;

    @InjectMocks
    private CompositeOrderService service;

    @Test
    void shouldCreateOrder() {
        UUID flightResourceId = UUID.randomUUID();
        UUID hotelResourceId = UUID.randomUUID();
        CreateOrderRequest request = new CreateOrderRequest(
                List.of(
                        new CreateBookingStepRequest(
                                BookingType.FLIGHT, flightResourceId),
                        new CreateBookingStepRequest(
                                BookingType.HOTEL, hotelResourceId)));

        when(repository.save(any(CompositeOrder.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        OrderResponse response = service.createOrder(request);

        assertEquals(OrderStatus.NEW, response.status());
        assertEquals(2, response.bookingSteps().size());

        assertEquals(
                BookingType.FLIGHT,
                response.bookingSteps().get(0).type());
        assertEquals(
                flightResourceId,
                response.bookingSteps().get(0).resourceId());

        assertEquals(
                BookingType.HOTEL,
                response.bookingSteps().get(1).type());
        assertEquals(
                hotelResourceId,
                response.bookingSteps().get(1).resourceId());

        verify(repository).save(any(CompositeOrder.class));
    }

    @Test
    void shouldRejectDuplicateBookingTypes() {
        CreateOrderRequest request = new CreateOrderRequest(
                List.of(
                        new CreateBookingStepRequest(
                                BookingType.FLIGHT, UUID.randomUUID()),
                        new CreateBookingStepRequest(
                                BookingType.FLIGHT, UUID.randomUUID())));

        assertThrows(
                InvalidBookingDataException.class,
                () -> service.createOrder(request));

        verify(repository, never()).save(any());
    }

    @Test
    void shouldThrowWhenOrderNotFound() {
        UUID id = UUID.randomUUID();

        when(repository.findById(id))
                .thenReturn(Optional.empty());

        assertThrows(
                OrderNotFoundException.class,
                () -> service.getOrder(id));

        verify(repository).findById(id);
    }

    @Test
    void shouldStartOrder() {
        UUID id = UUID.randomUUID();
        CompositeOrder order = CompositeOrder.create();
        order.startProcessing();
        order.complete();

        when(orchestrationService.orchestrate(id))
                .thenReturn(order);

        OrderResponse response = service.startOrder(id);

        assertEquals(OrderStatus.COMPLETED, response.status());
        verify(orchestrationService).orchestrate(id);
    }

    @Test
    void shouldThrowWhenStartingMissingOrder() {
        UUID id = UUID.randomUUID();

        when(orchestrationService.orchestrate(id))
                .thenThrow(new OrderNotFoundException(id));

        assertThrows(
                OrderNotFoundException.class,
                () -> service.startOrder(id));
    }

    @Test
    void shouldCancelOrder() {
        UUID id = UUID.randomUUID();
        CompositeOrder order = CompositeOrder.create();
        order.startProcessing();
        order.cancelManually();

        when(orchestrationService.cancel(id))
                .thenReturn(order);

        OrderResponse response = service.cancelOrder(id);

        assertEquals(OrderStatus.CANCELLED, response.status());
        verify(orchestrationService).cancel(id);
    }

    @Test
    void shouldThrowWhenCancellingMissingOrder() {
        UUID id = UUID.randomUUID();

        when(orchestrationService.cancel(id))
                .thenThrow(new OrderNotFoundException(id));

        assertThrows(
                OrderNotFoundException.class,
                () -> service.cancelOrder(id));
        verify(orchestrationService).cancel(id);
    }
}
