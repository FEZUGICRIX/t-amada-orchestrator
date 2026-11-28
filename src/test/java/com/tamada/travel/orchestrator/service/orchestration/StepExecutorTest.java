package com.tamada.travel.orchestrator.service.orchestration;

import com.tamada.travel.orchestrator.client.BookingClientRegistry;
import com.tamada.travel.orchestrator.client.dto.BookingRequest;
import com.tamada.travel.orchestrator.client.dto.BookingResponse;
import com.tamada.travel.orchestrator.model.BookingStatus;
import com.tamada.travel.orchestrator.model.BookingStep;
import com.tamada.travel.orchestrator.model.BookingType;
import com.tamada.travel.orchestrator.model.CompositeOrder;
import com.tamada.travel.orchestrator.model.PassengerType;
import com.tamada.travel.orchestrator.repository.CompositeOrderRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StepExecutorTest {

    private BookingClientRegistry clientRegistry;
    private CompositeOrderRepository repository;
    private TransactionTemplate transactionTemplate;
    private StepExecutor stepExecutor;

    @BeforeEach
    void setUp() {
        clientRegistry = mock(BookingClientRegistry.class);
        repository = mock(CompositeOrderRepository.class);
        transactionTemplate = mock(TransactionTemplate.class);
        when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(mock(TransactionStatus.class));
        });
        stepExecutor = new StepExecutor(clientRegistry, new StepResponseValidator(), repository, transactionTemplate);
    }

    @Test
    void shouldForwardSelectedHotelRoomVariantDetailsInBookingRequest() {
        UUID orderId = UUID.randomUUID();
        UUID hotelResourceId = UUID.randomUUID();
        UUID stepId = UUID.randomUUID();
        UUID externalBookingId = UUID.randomUUID();

        CompositeOrder order = CompositeOrder.create();
        ReflectionTestUtils.setField(order, "id", orderId);
        BookingStep hotelStep = order.addBookingStep(BookingType.HOTEL, hotelResourceId, new BigDecimal("14500.00"));
        ReflectionTestUtils.setField(hotelStep, "id", stepId);
        hotelStep.addRoom(
                "var-deluxe-hb-flex",
                "Deluxe",
                "Полупансион гибкий",
                "HB",
                new BigDecimal("14500.00"),
                "Бесплатная отмена за 48 часов до заезда",
                "Заезд с 14:00, выезд до 12:00"
        );
        hotelStep.startProcessing();

        when(repository.findById(orderId)).thenReturn(Optional.of(order));
        when(clientRegistry.book(eq(BookingType.HOTEL), any(BookingRequest.class)))
                .thenReturn(new BookingResponse(externalBookingId, stepId, "CONFIRMED", Instant.now()));

        StepExecutionResult result = stepExecutor.executeStep(orderId, stepId, BookingType.HOTEL, hotelResourceId);

        assertInstanceOf(StepExecutionResult.Success.class, result);
        assertEquals(BookingStatus.CONFIRMED, hotelStep.getStatus());

        ArgumentCaptor<BookingRequest> requestCaptor = ArgumentCaptor.forClass(BookingRequest.class);
        verify(clientRegistry).book(eq(BookingType.HOTEL), requestCaptor.capture());

        BookingRequest sentRequest = requestCaptor.getValue();
        assertEquals(hotelResourceId, sentRequest.resourceId());
        assertEquals(stepId, sentRequest.bookingReference());
        assertEquals(new BigDecimal("14500.00"), sentRequest.price());
        assertNotNull(sentRequest.rooms());
        assertEquals(1, sentRequest.rooms().size());

        BookingRequest.RoomDetail room = sentRequest.rooms().get(0);
        assertEquals("var-deluxe-hb-flex", room.variantId());
        assertEquals("Deluxe", room.category());
        assertEquals("Полупансион гибкий", room.tariff());
        assertEquals("HB", room.mealType());
        assertEquals(new BigDecimal("14500.00"), room.price());
        assertEquals("Бесплатная отмена за 48 часов до заезда", room.cancellationPolicy());
        assertEquals("Заезд с 14:00, выезд до 12:00", room.stayConditions());
    }

    @Test
    void shouldPassSeatsCountExcludingInfantsAndPassengersInBookingRequest() {
        CompositeOrder order = CompositeOrder.create();
        UUID orderId = UUID.randomUUID();
        UUID resourceId = UUID.randomUUID();
        BookingStep step = order.addBookingStep(BookingType.FLIGHT, resourceId, new BigDecimal("15000.00"));
        UUID stepId = UUID.randomUUID();
        ReflectionTestUtils.setField(order, "id", orderId);
        ReflectionTestUtils.setField(step, "id", stepId);
        step.startProcessing();

        order.addPassenger(PassengerType.ADULT, "Иван", "Иванов", LocalDate.of(1990, 5, 15), "Россия", "Паспорт РФ", "4510 123456");
        order.addPassenger(PassengerType.CHILD, "Анна", "Иванова", LocalDate.of(2016, 8, 20), "Россия", "Свидетельство о рождении", "II-АБ 654321");
        order.addPassenger(PassengerType.INFANT, "Михаил", "Иванов", LocalDate.of(2025, 1, 10), "Россия", "Свидетельство о рождении", "III-ВГ 789012");

        when(repository.findById(orderId)).thenReturn(Optional.of(order));
        UUID externalId = UUID.randomUUID();
        when(clientRegistry.book(eq(BookingType.FLIGHT), any(BookingRequest.class)))
                .thenReturn(new BookingResponse(externalId, stepId, "CONFIRMED", Instant.now()));

        StepExecutionResult result = stepExecutor.executeStep(orderId, stepId, BookingType.FLIGHT, resourceId);

        assertInstanceOf(StepExecutionResult.Success.class, result);

        ArgumentCaptor<BookingRequest> requestCaptor = ArgumentCaptor.forClass(BookingRequest.class);
        verify(clientRegistry).book(eq(BookingType.FLIGHT), requestCaptor.capture());

        BookingRequest sent = requestCaptor.getValue();
        assertEquals(resourceId, sent.resourceId());
        assertEquals(stepId, sent.bookingReference());
        assertEquals(2, sent.seatsCount());
        assertEquals(1, sent.adultsCount());
        assertEquals(1, sent.childrenCount());
        assertEquals(1, sent.infantsCount());
    }

    @Test
    void shouldLeaveSeatsCountAndCategoryCountsNullWhenOrderHasNoPassengers() {
        CompositeOrder order = CompositeOrder.create();
        UUID orderId = UUID.randomUUID();
        UUID resourceId = UUID.randomUUID();
        BookingStep step = order.addBookingStep(BookingType.HOTEL, resourceId, new BigDecimal("10000.00"));
        UUID stepId = UUID.randomUUID();
        ReflectionTestUtils.setField(order, "id", orderId);
        ReflectionTestUtils.setField(step, "id", stepId);
        step.startProcessing();

        when(repository.findById(orderId)).thenReturn(Optional.of(order));
        UUID externalId = UUID.randomUUID();
        when(clientRegistry.book(eq(BookingType.HOTEL), any(BookingRequest.class)))
                .thenReturn(new BookingResponse(externalId, stepId, "CONFIRMED", Instant.now()));

        StepExecutionResult result = stepExecutor.executeStep(orderId, stepId, BookingType.HOTEL, resourceId);

        assertInstanceOf(StepExecutionResult.Success.class, result);

        ArgumentCaptor<BookingRequest> requestCaptor = ArgumentCaptor.forClass(BookingRequest.class);
        verify(clientRegistry).book(eq(BookingType.HOTEL), requestCaptor.capture());

        BookingRequest sent = requestCaptor.getValue();
        assertNull(sent.seatsCount());
        assertNull(sent.adultsCount());
        assertNull(sent.childrenCount());
        assertNull(sent.infantsCount());
    }
}