package com.tamada.travel.orchestrator.service;

import com.tamada.travel.orchestrator.dto.CreateBookingStepRequest;
import com.tamada.travel.orchestrator.dto.CreateOrderRequest;
import com.tamada.travel.orchestrator.dto.CustomerContactRequest;
import com.tamada.travel.orchestrator.dto.OrderResponse;
import com.tamada.travel.orchestrator.dto.PassengerRequest;
import com.tamada.travel.orchestrator.exception.InvalidBookingDataException;
import com.tamada.travel.orchestrator.exception.OrderNotFoundException;
import com.tamada.travel.orchestrator.model.BookingType;
import com.tamada.travel.orchestrator.model.CompositeOrder;
import com.tamada.travel.orchestrator.model.OrderStatus;
import com.tamada.travel.orchestrator.model.PassengerType;
import com.tamada.travel.orchestrator.repository.CompositeOrderRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
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

    @Mock
    private OutboxService outboxService;

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
        verify(outboxService).saveOrderCreatedEvent(any(CompositeOrder.class));
    }

    @Test
    void shouldCreateOrderWithRoundTripAndAllOptionsAndPassengersAndContact() {
        UUID flightOutId = UUID.randomUUID();
        UUID flightReturnId = UUID.randomUUID();
        UUID hotelId = UUID.randomUUID();
        UUID insuranceId = UUID.randomUUID();
        UUID transferId = UUID.randomUUID();

        List<CreateBookingStepRequest> bookings = List.of(
                new CreateBookingStepRequest(BookingType.FLIGHT, flightOutId),
                new CreateBookingStepRequest(BookingType.FLIGHT, flightReturnId),
                new CreateBookingStepRequest(BookingType.HOTEL, hotelId),
                new CreateBookingStepRequest(BookingType.INSURANCE, insuranceId),
                new CreateBookingStepRequest(BookingType.TRANSFER, transferId)
        );

        List<PassengerRequest> passengers = List.of(
                new PassengerRequest(
                        PassengerType.ADULT,
                        "Иван",
                        "Иванов",
                        LocalDate.of(1990, 5, 15),
                        "Россия",
                        "Паспорт РФ",
                        "4510 123456"
                ),
                new PassengerRequest(
                        PassengerType.CHILD,
                        "Анна",
                        "Иванова",
                        LocalDate.of(2015, 8, 20),
                        "Россия",
                        "Свидетельство о рождении",
                        "II-АБ 654321"
                ),
                new PassengerRequest(
                        PassengerType.INFANT,
                        "Михаил",
                        "Иванов",
                        LocalDate.of(2024, 1, 10),
                        "Россия",
                        "Свидетельство о рождении",
                        "III-ВГ 789012"
                )
        );

        CustomerContactRequest contact = new CustomerContactRequest(
                "+79991234567",
                "ivanov@example.com"
        );

        CreateOrderRequest request = new CreateOrderRequest(bookings, passengers, contact);

        when(repository.save(any(CompositeOrder.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        OrderResponse response = service.createOrder(request);

        assertEquals(OrderStatus.NEW, response.status());
        assertEquals(5, response.bookingSteps().size());
        assertEquals(3, response.passengers().size());
        assertNotNull(response.customerContact());
        assertEquals("+79991234567", response.customerContact().phone());
        assertEquals("ivanov@example.com", response.customerContact().email());

        // Check passengers mapping
        assertEquals("Иван", response.passengers().get(0).firstName());
        assertEquals(PassengerType.ADULT, response.passengers().get(0).type());
        assertEquals("Анна", response.passengers().get(1).firstName());
        assertEquals(PassengerType.CHILD, response.passengers().get(1).type());
        assertEquals("Михаил", response.passengers().get(2).firstName());
        assertEquals(PassengerType.INFANT, response.passengers().get(2).type());

        // Check bookings mapping
        assertEquals(BookingType.FLIGHT, response.bookingSteps().get(0).type());
        assertEquals(flightOutId, response.bookingSteps().get(0).resourceId());
        assertEquals(BookingType.FLIGHT, response.bookingSteps().get(1).type());
        assertEquals(flightReturnId, response.bookingSteps().get(1).resourceId());
        assertEquals(BookingType.HOTEL, response.bookingSteps().get(2).type());
        assertEquals(BookingType.INSURANCE, response.bookingSteps().get(3).type());
        assertEquals(BookingType.TRANSFER, response.bookingSteps().get(4).type());

        verify(repository).save(any(CompositeOrder.class));
        verify(outboxService).saveOrderCreatedEvent(any(CompositeOrder.class));
    }

    @Test
    void shouldRejectDuplicateBookingTypes() {
        CreateOrderRequest request = new CreateOrderRequest(
                List.of(
                        new CreateBookingStepRequest(
                                BookingType.HOTEL, UUID.randomUUID()),
                        new CreateBookingStepRequest(
                                BookingType.HOTEL, UUID.randomUUID())));

        assertThrows(
                InvalidBookingDataException.class,
                () -> service.createOrder(request));

        verify(repository, never()).save(any());
    }

    @Test
    void shouldRejectMoreThanTwoFlights() {
        CreateOrderRequest request = new CreateOrderRequest(
                List.of(
                        new CreateBookingStepRequest(BookingType.FLIGHT, UUID.randomUUID()),
                        new CreateBookingStepRequest(BookingType.FLIGHT, UUID.randomUUID()),
                        new CreateBookingStepRequest(BookingType.FLIGHT, UUID.randomUUID())));

        assertThrows(
                InvalidBookingDataException.class,
                () -> service.createOrder(request));

        verify(repository, never()).save(any());
    }

    @Test
    void shouldRejectDuplicateResourceIds() {
        UUID resourceId = UUID.randomUUID();
        CreateOrderRequest request = new CreateOrderRequest(
                List.of(
                        new CreateBookingStepRequest(BookingType.FLIGHT, resourceId),
                        new CreateBookingStepRequest(BookingType.FLIGHT, resourceId)));

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
