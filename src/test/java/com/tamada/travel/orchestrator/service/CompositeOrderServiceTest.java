package com.tamada.travel.orchestrator.service;

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

    @InjectMocks
    private CompositeOrderService service;

    @Test
    void shouldCreateOrder() {
        CreateOrderRequest request = new CreateOrderRequest(
                List.of(
                        BookingType.FLIGHT,
                        BookingType.HOTEL
                )
        );

        when(repository.save(any(CompositeOrder.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        OrderResponse response = service.createOrder(request);

        assertEquals(OrderStatus.NEW, response.status());
        assertEquals(2, response.bookingSteps().size());

        assertEquals(
                BookingType.FLIGHT,
                response.bookingSteps().get(0).type()
        );

        assertEquals(
                BookingType.HOTEL,
                response.bookingSteps().get(1).type()
        );

        verify(repository).save(any(CompositeOrder.class));
    }

    @Test
    void shouldRejectDuplicateBookingTypes() {
        CreateOrderRequest request = new CreateOrderRequest(
                List.of(
                        BookingType.FLIGHT,
                        BookingType.FLIGHT
                )
        );

        assertThrows(
                InvalidBookingDataException.class,
                () -> service.createOrder(request)
        );

        verify(repository, never()).save(any());
    }

    @Test
    void shouldThrowWhenOrderNotFound() {
        UUID id = UUID.randomUUID();

        when(repository.findById(id))
                .thenReturn(Optional.empty());

        assertThrows(
                OrderNotFoundException.class,
                () -> service.getOrder(id)
        );

        verify(repository).findById(id);
    }
}