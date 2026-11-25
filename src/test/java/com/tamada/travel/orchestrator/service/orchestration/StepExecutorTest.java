package com.tamada.travel.orchestrator.service.orchestration;

import com.tamada.travel.orchestrator.client.BookingClientRegistry;
import com.tamada.travel.orchestrator.client.dto.BookingRequest;
import com.tamada.travel.orchestrator.client.dto.BookingResponse;
import com.tamada.travel.orchestrator.model.BookingStep;
import com.tamada.travel.orchestrator.model.BookingType;
import com.tamada.travel.orchestrator.model.CompositeOrder;
import com.tamada.travel.orchestrator.model.PassengerType;
import com.tamada.travel.orchestrator.repository.CompositeOrderRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
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
    void shouldPassSeatsCountExcludingInfantsAndPassengersInBookingRequest() {
        CompositeOrder order = CompositeOrder.create();
        UUID orderId = UUID.randomUUID();
        UUID resourceId = UUID.randomUUID();
        BookingStep step = order.addBookingStep(BookingType.FLIGHT, resourceId, new BigDecimal("15000.00"));
        UUID stepId = UUID.randomUUID();
        setField(order, "id", orderId);
        setField(step, "id", stepId);
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
        setField(order, "id", orderId);
        setField(step, "id", stepId);
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

    private static void setField(Object target, String fieldName, Object value) {
        try {
            var field = target.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            field.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
