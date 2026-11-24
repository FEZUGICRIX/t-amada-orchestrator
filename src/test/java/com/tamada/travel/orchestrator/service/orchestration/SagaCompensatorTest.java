package com.tamada.travel.orchestrator.service.orchestration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tamada.travel.orchestrator.client.BookingClient;
import com.tamada.travel.orchestrator.client.BookingClientRegistry;
import com.tamada.travel.orchestrator.client.dto.CancelBookingResponse;
import com.tamada.travel.orchestrator.config.SagaCompensationProperties;
import com.tamada.travel.orchestrator.exception.ExternalBookingErrorType;
import com.tamada.travel.orchestrator.exception.ExternalBookingException;
import com.tamada.travel.orchestrator.model.BookingStatus;
import com.tamada.travel.orchestrator.model.BookingType;
import com.tamada.travel.orchestrator.model.CompositeOrder;
import com.tamada.travel.orchestrator.model.OrderStatus;
import com.tamada.travel.orchestrator.repository.CompositeOrderRepository;
import com.tamada.travel.orchestrator.service.error.StepErrorMapper;
import com.tamada.travel.orchestrator.service.error.UserErrorMessageResolver;
import com.tamada.travel.orchestrator.service.sse.OrderSseService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SagaCompensatorTest {

    @Mock
    private BookingClientRegistry clientRegistry;
    @Mock
    private BookingClient flightClient;
    @Mock
    private BookingClient hotelClient;
    @Mock
    private StepResponseValidator responseValidator;
    @Mock
    private CompositeOrderRepository repository;
    @Mock
    private TransactionTemplate transactionTemplate;
    @Mock
    private OrderSseService sseService;

    private UserErrorMessageResolver errorMessageResolver;
    private StepErrorMapper stepErrorMapper;

    @BeforeEach
    void setUp() {
        errorMessageResolver = new UserErrorMessageResolver();
        stepErrorMapper = new StepErrorMapper(errorMessageResolver, new ObjectMapper());

        when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(new SimpleTransactionStatus());
        });
    }

    @Test
    void shouldStopOnFirstFailureWhenContinueOnFailureIsFalse() {
        SagaCompensationProperties properties = new SagaCompensationProperties(
                true, Duration.ofMinutes(2), Duration.ofSeconds(60), Duration.ofSeconds(60), true, false
        );

        SagaCompensator compensator = new SagaCompensator(
                clientRegistry, responseValidator, repository, transactionTemplate,
                errorMessageResolver, sseService, stepErrorMapper, null, null, properties
        );

        UUID orderId = UUID.randomUUID();
        CompositeOrder order = CompositeOrder.create();
        ReflectionTestUtils.setField(order, "id", orderId);
        var flight = order.addBookingStep(BookingType.FLIGHT, UUID.randomUUID(), new BigDecimal("1000.00"));
        ReflectionTestUtils.setField(flight, "id", UUID.randomUUID());
        var hotel = order.addBookingStep(BookingType.HOTEL, UUID.randomUUID(), new BigDecimal("2000.00"));
        ReflectionTestUtils.setField(hotel, "id", UUID.randomUUID());

        order.startProcessing();
        flight.startProcessing();
        flight.confirm(UUID.randomUUID().toString());
        hotel.startProcessing();
        hotel.confirm(UUID.randomUUID().toString());

        when(repository.findById(orderId)).thenReturn(Optional.of(order));
        when(clientRegistry.cancel(eq(BookingType.HOTEL), any(com.tamada.travel.orchestrator.client.dto.CancelBookingRequest.class))).thenThrow(
                new ExternalBookingException(HttpStatus.SERVICE_UNAVAILABLE, BookingType.HOTEL, ExternalBookingErrorType.SYSTEM_FAILURE, "Hotel service down", "/api/v1/hotel/cancel", null)
        );

        assertThrows(ExternalBookingException.class, () ->
                compensator.cancelConfirmedSteps(orderId, false)
        );

        verify(clientRegistry).cancel(eq(BookingType.HOTEL), any(com.tamada.travel.orchestrator.client.dto.CancelBookingRequest.class));
        verify(clientRegistry, never()).cancel(eq(BookingType.FLIGHT), any(com.tamada.travel.orchestrator.client.dto.CancelBookingRequest.class));
    }

    @Test
    void shouldContinueCompensatingRemainingStepsWhenContinueOnFailureIsTrue() {
        SagaCompensationProperties properties = new SagaCompensationProperties(
                true, Duration.ofMinutes(2), Duration.ofSeconds(60), Duration.ofSeconds(60), true, true
        );

        SagaCompensator compensator = new SagaCompensator(
                clientRegistry, responseValidator, repository, transactionTemplate,
                errorMessageResolver, sseService, stepErrorMapper, null, null, properties
        );

        UUID orderId = UUID.randomUUID();
        CompositeOrder order = CompositeOrder.create();
        ReflectionTestUtils.setField(order, "id", orderId);
        var flight = order.addBookingStep(BookingType.FLIGHT, UUID.randomUUID(), new BigDecimal("1000.00"));
        ReflectionTestUtils.setField(flight, "id", UUID.randomUUID());
        var hotel = order.addBookingStep(BookingType.HOTEL, UUID.randomUUID(), new BigDecimal("2000.00"));
        ReflectionTestUtils.setField(hotel, "id", UUID.randomUUID());

        order.startProcessing();
        flight.startProcessing();
        flight.confirm(UUID.randomUUID().toString());
        hotel.startProcessing();
        hotel.confirm(UUID.randomUUID().toString());

        when(repository.findById(orderId)).thenReturn(Optional.of(order));

        when(clientRegistry.cancel(eq(BookingType.HOTEL), any(com.tamada.travel.orchestrator.client.dto.CancelBookingRequest.class))).thenThrow(
                new ExternalBookingException(HttpStatus.SERVICE_UNAVAILABLE, BookingType.HOTEL, ExternalBookingErrorType.SYSTEM_FAILURE, "Hotel service down", "/api/v1/hotel/cancel", null)
        );
        when(clientRegistry.cancel(eq(BookingType.FLIGHT), any(com.tamada.travel.orchestrator.client.dto.CancelBookingRequest.class))).thenReturn(
                new CancelBookingResponse(UUID.randomUUID(), "CANCELLED", Instant.now())
        );

        assertThrows(ExternalBookingException.class, () ->
                compensator.cancelConfirmedSteps(orderId, false)
        );

        // Verify both were called!
        verify(clientRegistry).cancel(eq(BookingType.HOTEL), any(com.tamada.travel.orchestrator.client.dto.CancelBookingRequest.class));
        verify(clientRegistry).cancel(eq(BookingType.FLIGHT), any(com.tamada.travel.orchestrator.client.dto.CancelBookingRequest.class));
    }
}
