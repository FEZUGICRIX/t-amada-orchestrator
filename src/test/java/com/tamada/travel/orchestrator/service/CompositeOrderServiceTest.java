package com.tamada.travel.orchestrator.service;

import com.tamada.travel.orchestrator.dto.BookingStepResponse;
import com.tamada.travel.orchestrator.dto.CreateBookingStepRequest;
import com.tamada.travel.orchestrator.dto.CreateOrderRequest;
import com.tamada.travel.orchestrator.dto.CustomerContactRequest;
import com.tamada.travel.orchestrator.dto.HotelRoomRequest;
import com.tamada.travel.orchestrator.dto.OrderResponse;
import com.tamada.travel.orchestrator.dto.PassengerRequest;
import com.tamada.travel.orchestrator.dto.ReplaceHotelRequest;
import com.tamada.travel.orchestrator.exception.InvalidBookingDataException;
import com.tamada.travel.orchestrator.exception.OrderNotFoundException;
import com.tamada.travel.orchestrator.model.BookingStatus;
import com.tamada.travel.orchestrator.model.BookingStep;
import com.tamada.travel.orchestrator.model.BookingType;
import com.tamada.travel.orchestrator.model.CompositeOrder;
import com.tamada.travel.orchestrator.model.OrderStatus;
import com.tamada.travel.orchestrator.model.PassengerType;
import com.tamada.travel.orchestrator.repository.CompositeOrderRepository;
import com.tamada.travel.orchestrator.service.orchestration.SagaCompensator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
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

    @Mock
    private SagaCompensator compensator;

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
    void shouldCreateOrderWithPricesAndCalculateServiceFee() {
        UUID flightResourceId = UUID.randomUUID();
        UUID hotelResourceId = UUID.randomUUID();
        CreateOrderRequest request = new CreateOrderRequest(
                List.of(
                        new CreateBookingStepRequest(
                                BookingType.FLIGHT, flightResourceId, new BigDecimal("20000.00")),
                        new CreateBookingStepRequest(
                                BookingType.HOTEL, hotelResourceId, new BigDecimal("10000.00"))));

        when(repository.save(any(CompositeOrder.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        OrderResponse response = service.createOrder(request);

        assertEquals(OrderStatus.NEW, response.status());
        assertEquals(2, response.bookingSteps().size());
        assertEquals(new BigDecimal("20000.00"), response.bookingSteps().get(0).price());
        assertEquals(new BigDecimal("10000.00"), response.bookingSteps().get(1).price());

        // 30000 * 0.0067 = 201.00
        assertEquals(new BigDecimal("30000.00"), response.basePrice());
        assertEquals(new BigDecimal("201.00"), response.serviceFee());
        assertEquals(new BigDecimal("30201.00"), response.totalPrice());

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
                                BookingType.FLIGHT, UUID.randomUUID()),
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
    void shouldRejectOrderWithoutFlight() {
        CreateOrderRequest request = new CreateOrderRequest(
                List.of(new CreateBookingStepRequest(BookingType.HOTEL, UUID.randomUUID()))
        );

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
        order.startCancelling();
        order.cancel();

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

    @Test
    void shouldCreateOrderWithMultipleHotelRoomsAndCalculatePricing() {
        UUID hotelId = UUID.randomUUID();

        List<HotelRoomRequest> rooms = List.of(
                new HotelRoomRequest(
                        "var-luxe-bb",
                        "Люкс",
                        "С завтраком",
                        "BB",
                        new BigDecimal("12000.00"),
                        "Бесплатная отмена до 14:00 дня заезда",
                        "Завтрак включен"
                ),
                new HotelRoomRequest("Стандарт", "Без питания", new BigDecimal("8000.00"), "Невозвратный тариф")
        );

        CreateBookingStepRequest hotelStepReq = new CreateBookingStepRequest(
                BookingType.HOTEL,
                hotelId,
                null,
                2,
                rooms
        );

        CreateBookingStepRequest flightStep = new CreateBookingStepRequest(
                BookingType.FLIGHT,
                UUID.randomUUID(),
                BigDecimal.ZERO
        );

        CreateOrderRequest request = new CreateOrderRequest(List.of(flightStep, hotelStepReq));

        when(repository.save(any(CompositeOrder.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        OrderResponse response = service.createOrder(request);

        assertEquals(OrderStatus.NEW, response.status());
        assertEquals(2, response.bookingSteps().size());

        var stepResponse = response.bookingSteps().get(1);
        assertEquals(BookingType.HOTEL, stepResponse.type());
        assertEquals(hotelId, stepResponse.resourceId());
        assertEquals(new BigDecimal("20000.00"), stepResponse.price());
        assertEquals(2, stepResponse.roomsCount());
        assertNotNull(stepResponse.rooms());
        assertEquals(2, stepResponse.rooms().size());

        var room1 = stepResponse.rooms().get(0);
        assertEquals("var-luxe-bb", room1.variantId());
        assertEquals("Люкс", room1.category());
        assertEquals("С завтраком", room1.tariff());
        assertEquals("BB", room1.mealType());
        assertEquals(new BigDecimal("12000.00"), room1.price());
        assertEquals("Бесплатная отмена до 14:00 дня заезда", room1.cancellationPolicy());
        assertEquals("Завтрак включен", room1.stayConditions());

        var room2 = stepResponse.rooms().get(1);
        assertEquals("Стандарт", room2.category());
        assertEquals("Без питания", room2.tariff());
        assertEquals(new BigDecimal("8000.00"), room2.price());
        assertEquals("Невозвратный тариф", room2.stayConditions());

        // Base price: 20000.00, fee (0.67%): 134.00, total: 20134.00
        assertEquals(new BigDecimal("20000.00"), response.basePrice());
        assertEquals(new BigDecimal("134.00"), response.serviceFee());
        assertEquals(new BigDecimal("20134.00"), response.totalPrice());

        verify(repository).save(any(CompositeOrder.class));
        verify(outboxService).saveOrderCreatedEvent(any(CompositeOrder.class));
    }

    @Test
    void shouldRejectWhenRoomsCountDoesNotMatchRoomsSize() {
        CreateBookingStepRequest flightStep = new CreateBookingStepRequest(
                BookingType.FLIGHT,
                UUID.randomUUID()
        );
        CreateBookingStepRequest hotelStepReq = new CreateBookingStepRequest(
                BookingType.HOTEL,
                UUID.randomUUID(),
                new BigDecimal("10000.00"),
                3,
                List.of(
                        new HotelRoomRequest("Люкс", "С завтраком", new BigDecimal("5000.00")),
                        new HotelRoomRequest("Стандарт", "Базовый", new BigDecimal("5000.00"))
                )
        );

        CreateOrderRequest request = new CreateOrderRequest(List.of(flightStep, hotelStepReq));

        assertThrows(
                InvalidBookingDataException.class,
                () -> service.createOrder(request)
        );
    }

    @Test
    void shouldRejectWhenRoomsCountSpecifiedWithoutRooms() {
        CreateBookingStepRequest flightStep = new CreateBookingStepRequest(
                BookingType.FLIGHT,
                UUID.randomUUID()
        );
        CreateBookingStepRequest hotelStepReq = new CreateBookingStepRequest(
                BookingType.HOTEL,
                UUID.randomUUID(),
                new BigDecimal("10000.00"),
                2,
                null
        );

        CreateOrderRequest request = new CreateOrderRequest(List.of(flightStep, hotelStepReq));

        assertThrows(
                InvalidBookingDataException.class,
                () -> service.createOrder(request)
        );
    }

    @Test
    void shouldRejectRoomsForNonHotelBookingStep() {
        CreateBookingStepRequest flightStep = new CreateBookingStepRequest(
                BookingType.FLIGHT,
                UUID.randomUUID(),
                new BigDecimal("10000.00"),
                1,
                List.of(new HotelRoomRequest("Люкс", "Тариф", new BigDecimal("10000.00")))
        );

        CreateOrderRequest request = new CreateOrderRequest(List.of(flightStep));

        assertThrows(
                InvalidBookingDataException.class,
                () -> service.createOrder(request)
        );
    }

    @Test
    void shouldRejectWhenStepPriceDoesNotMatchTotalRoomsPrice() {
        CreateBookingStepRequest flightStep = new CreateBookingStepRequest(
                BookingType.FLIGHT,
                UUID.randomUUID()
        );
        CreateBookingStepRequest hotelStepReq = new CreateBookingStepRequest(
                BookingType.HOTEL,
                UUID.randomUUID(),
                new BigDecimal("15000.00"),
                2,
                List.of(
                        new HotelRoomRequest("Люкс", "С завтраком", new BigDecimal("6000.00")),
                        new HotelRoomRequest("Стандарт", "Базовый", new BigDecimal("6000.00"))
                )
        );

        CreateOrderRequest request = new CreateOrderRequest(List.of(flightStep, hotelStepReq));

        assertThrows(
                InvalidBookingDataException.class,
                () -> service.createOrder(request)
        );
    }

    @Test
    void shouldStartOrderAsyncSuccessfully() {
        UUID orderId = UUID.randomUUID();
        CompositeOrder order = CompositeOrder.create();
        org.springframework.test.util.ReflectionTestUtils.setField(order, "id", orderId);
        order.addBookingStep(BookingType.FLIGHT, UUID.randomUUID());

        when(repository.findById(orderId)).thenReturn(Optional.of(order));
        when(repository.save(any(CompositeOrder.class))).thenAnswer(invocation -> invocation.getArgument(0));

        OrderResponse response = service.startOrderAsync(orderId);

        assertNotNull(response);
        assertEquals(OrderStatus.PROCESSING, response.status());
        verify(repository).save(order);
    }

    @Test
    void shouldRejectStartOrderAsyncWhenOrderNotInNewStatus() {
        UUID orderId = UUID.randomUUID();
        CompositeOrder order = CompositeOrder.create();
        org.springframework.test.util.ReflectionTestUtils.setField(order, "id", orderId);
        order.addBookingStep(BookingType.FLIGHT, UUID.randomUUID());
        order.startProcessing();

        when(repository.findById(orderId)).thenReturn(Optional.of(order));

        assertThrows(
                com.tamada.travel.orchestrator.exception.InvalidStateTransitionException.class,
                () -> service.startOrderAsync(orderId)
        );
        verify(repository, never()).save(order);
    }

    @Test
    void shouldStartOrderAsyncFromFailedStatusSuccessfully() {
        UUID orderId = UUID.randomUUID();
        CompositeOrder order = CompositeOrder.create();
        org.springframework.test.util.ReflectionTestUtils.setField(order, "id", orderId);
        order.addBookingStep(BookingType.FLIGHT, UUID.randomUUID());
        order.startProcessing();
        order.fail("Previous failure", "Hotel failed");

        when(repository.findById(orderId)).thenReturn(Optional.of(order));
        when(repository.save(any(CompositeOrder.class))).thenAnswer(invocation -> invocation.getArgument(0));

        OrderResponse response = service.startOrderAsync(orderId);

        assertNotNull(response);
        assertEquals(OrderStatus.PROCESSING, response.status());
        verify(repository).save(order);
    }

    @Test
    void shouldReplaceHotelSuccessfullyWhenHotelFailed() {
        UUID orderId = UUID.randomUUID();
        UUID oldHotelId = UUID.randomUUID();
        UUID newHotelId = UUID.randomUUID();

        CompositeOrder order = CompositeOrder.create();
        org.springframework.test.util.ReflectionTestUtils.setField(order, "id", orderId);
        BookingStep flightStep = order.addBookingStep(BookingType.FLIGHT, UUID.randomUUID(), new BigDecimal("15000.00"));
        org.springframework.test.util.ReflectionTestUtils.setField(flightStep, "id", UUID.randomUUID());
        BookingStep hotelStep = order.addBookingStep(BookingType.HOTEL, oldHotelId, new BigDecimal("8000.00"));
        org.springframework.test.util.ReflectionTestUtils.setField(hotelStep, "id", UUID.randomUUID());

        order.startProcessing();
        flightStep.startProcessing();
        flightStep.confirm("flight-ext-123");
        hotelStep.startProcessing();
        hotelStep.fail("409", "Not available", "Room not available", null);
        order.fail("Room not available", "Room not available");

        when(repository.findById(orderId)).thenReturn(Optional.of(order));
        when(repository.save(any(CompositeOrder.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ReplaceHotelRequest request = new ReplaceHotelRequest(newHotelId, new BigDecimal("9500.00"));
        OrderResponse response = service.replaceHotel(orderId, request);

        assertNotNull(response);
        BookingStepResponse updatedHotel = response.bookingSteps().stream()
                .filter(s -> s.type() == BookingType.HOTEL)
                .findFirst()
                .orElseThrow();

        assertEquals(newHotelId, updatedHotel.resourceId());
        assertEquals(BookingStatus.PENDING, updatedHotel.status());
        assertEquals(new BigDecimal("9500.00"), updatedHotel.price());
        // Verify no compensator call because old hotel was FAILED, not CONFIRMED
        verify(compensator, never()).compensateSingleStep(any(), any(), any(), any());
        verify(repository).save(order);
    }

    @Test
    void shouldReplaceHotelWithPartialCompensationWhenHotelConfirmed() {
        UUID orderId = UUID.randomUUID();
        UUID oldHotelId = UUID.randomUUID();
        UUID newHotelId = UUID.randomUUID();
        UUID hotelStepId = UUID.randomUUID();

        CompositeOrder order = CompositeOrder.create();
        org.springframework.test.util.ReflectionTestUtils.setField(order, "id", orderId);
        BookingStep flightStep = order.addBookingStep(BookingType.FLIGHT, UUID.randomUUID(), new BigDecimal("15000.00"));
        org.springframework.test.util.ReflectionTestUtils.setField(flightStep, "id", UUID.randomUUID());
        BookingStep hotelStep = order.addBookingStep(BookingType.HOTEL, oldHotelId, new BigDecimal("8000.00"));
        org.springframework.test.util.ReflectionTestUtils.setField(hotelStep, "id", hotelStepId);

        order.startProcessing();
        flightStep.startProcessing();
        flightStep.confirm(UUID.randomUUID().toString());
        hotelStep.startProcessing();
        hotelStep.confirm(UUID.randomUUID().toString());
        order.complete();

        when(repository.findById(orderId)).thenReturn(Optional.of(order));
        when(repository.save(any(CompositeOrder.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ReplaceHotelRequest request = new ReplaceHotelRequest(newHotelId, new BigDecimal("11000.00"));
        OrderResponse response = service.replaceHotel(orderId, request);

        assertNotNull(response);
        // Verify partial compensation called for hotel
        verify(compensator).compensateSingleStep(eq(orderId), eq(hotelStepId), any(), any());

        BookingStepResponse updatedHotel = response.bookingSteps().stream()
                .filter(s -> s.type() == BookingType.HOTEL)
                .findFirst()
                .orElseThrow();

        assertEquals(newHotelId, updatedHotel.resourceId());
        assertEquals(BookingStatus.PENDING, updatedHotel.status());
        // Flight ticket is preserved
        BookingStepResponse flight = response.bookingSteps().stream()
                .filter(s -> s.type() == BookingType.FLIGHT)
                .findFirst()
                .orElseThrow();
        assertEquals(BookingStatus.CONFIRMED, flight.status());
    }

    @Test
    void shouldReplaceStepSuccessfully() {
        UUID orderId = UUID.randomUUID();
        UUID stepId = UUID.randomUUID();
        UUID newResourceId = UUID.randomUUID();

        CompositeOrder order = CompositeOrder.create();
        org.springframework.test.util.ReflectionTestUtils.setField(order, "id", orderId);
        BookingStep transferStep = order.addBookingStep(BookingType.TRANSFER, UUID.randomUUID(), new BigDecimal("2000.00"));
        org.springframework.test.util.ReflectionTestUtils.setField(transferStep, "id", stepId);

        when(repository.findById(orderId)).thenReturn(Optional.of(order));
        when(repository.save(any(CompositeOrder.class))).thenAnswer(invocation -> invocation.getArgument(0));

        CreateBookingStepRequest request = new CreateBookingStepRequest(BookingType.TRANSFER, newResourceId, new BigDecimal("2500.00"));
        OrderResponse response = service.replaceStep(orderId, stepId, request);

        assertNotNull(response);
        BookingStepResponse updated = response.bookingSteps().getFirst();
        assertEquals(newResourceId, updated.resourceId());
        assertEquals(BookingStatus.PENDING, updated.status());
        assertEquals(new BigDecimal("2500.00"), updated.price());
    }

    @Test
    void shouldCancelStepSuccessfully() {
        UUID orderId = UUID.randomUUID();
        UUID stepId = UUID.randomUUID();

        CompositeOrder order = CompositeOrder.create();
        org.springframework.test.util.ReflectionTestUtils.setField(order, "id", orderId);
        BookingStep step = order.addBookingStep(BookingType.HOTEL, UUID.randomUUID(), new BigDecimal("8000.00"));
        org.springframework.test.util.ReflectionTestUtils.setField(step, "id", stepId);
        order.startProcessing();
        step.startProcessing();
        step.confirm(UUID.randomUUID().toString());

        when(repository.findById(orderId)).thenReturn(Optional.of(order));
        when(repository.save(any(CompositeOrder.class))).thenAnswer(invocation -> invocation.getArgument(0));

        OrderResponse response = service.cancelStep(orderId, stepId);

        assertNotNull(response);
        verify(compensator).compensateSingleStep(eq(orderId), eq(stepId), any(), any());
    }
}
