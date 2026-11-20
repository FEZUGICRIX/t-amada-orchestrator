package com.tamada.travel.orchestrator.client;

import com.tamada.travel.orchestrator.client.dto.BookingRequest;
import com.tamada.travel.orchestrator.client.dto.BookingResponse;
import com.tamada.travel.orchestrator.client.dto.CancelBookingRequest;
import com.tamada.travel.orchestrator.client.dto.CancelBookingResponse;
import com.tamada.travel.orchestrator.exception.ExternalBookingErrorType;
import com.tamada.travel.orchestrator.exception.ExternalBookingException;
import com.tamada.travel.orchestrator.model.BookingType;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CircuitBreakerBookingClientTest {

    private BookingClient mockDelegate;
    private CircuitBreaker circuitBreaker;
    private CircuitBreakerBookingClient client;

    @BeforeEach
    void setUp() {
        mockDelegate = mock(BookingClient.class);
        when(mockDelegate.type()).thenReturn(BookingType.HOTEL);

        CircuitBreakerConfig config = CircuitBreakerConfig.custom()
                .slidingWindowSize(4)
                .minimumNumberOfCalls(2)
                .failureRateThreshold(50.0f)
                .waitDurationInOpenState(Duration.ofMillis(500))
                .permittedNumberOfCallsInHalfOpenState(2)
                .recordException(throwable -> {
                    if (throwable instanceof ExternalBookingException ex) {
                        return ex.isRetryable();
                    }
                    return true;
                })
                .ignoreException(throwable -> throwable instanceof ExternalBookingException ex && !ex.isRetryable())
                .build();

        circuitBreaker = CircuitBreaker.of("HOTEL", config);
        client = new CircuitBreakerBookingClient(mockDelegate, circuitBreaker);
    }

    @Test
    void shouldDelegateBookSuccessfullyWhenCircuitIsClosed() {
        BookingRequest request = new BookingRequest(UUID.randomUUID(), UUID.randomUUID());
        BookingResponse expectedResponse = new BookingResponse(UUID.randomUUID(), request.bookingReference(), "CONFIRMED", Instant.now());
        when(mockDelegate.book(request)).thenReturn(expectedResponse);

        BookingResponse actualResponse = client.book(request);

        assertSame(expectedResponse, actualResponse);
        assertEquals(CircuitBreaker.State.CLOSED, circuitBreaker.getState());
        verify(mockDelegate, times(1)).book(request);
    }

    @Test
    void shouldDelegateCancelSuccessfullyWhenCircuitIsClosed() {
        CancelBookingRequest request = new CancelBookingRequest(UUID.randomUUID());
        CancelBookingResponse expectedResponse = new CancelBookingResponse(request.bookingId(), "CANCELLED", Instant.now());
        when(mockDelegate.cancel(request)).thenReturn(expectedResponse);

        CancelBookingResponse actualResponse = client.cancel(request);

        assertSame(expectedResponse, actualResponse);
        assertEquals(CircuitBreaker.State.CLOSED, circuitBreaker.getState());
        verify(mockDelegate, times(1)).cancel(request);
    }

    @Test
    void shouldNotTripCircuitBreakerOnBusinessErrors409() {
        BookingRequest request = new BookingRequest(UUID.randomUUID(), UUID.randomUUID());
        ExternalBookingException conflict = new ExternalBookingException(
                HttpStatus.CONFLICT,
                BookingType.HOTEL,
                ExternalBookingErrorType.RESOURCE_UNAVAILABLE,
                "Room unavailable",
                "/book",
                null
        );
        when(mockDelegate.book(request)).thenThrow(conflict);

        // Multiple business errors
        for (int i = 0; i < 5; i++) {
            assertThrows(ExternalBookingException.class, () -> client.book(request));
        }

        // Circuit breaker must remain CLOSED because 409 is not retryable / infrastructure failure
        assertEquals(CircuitBreaker.State.CLOSED, circuitBreaker.getState());
        assertEquals(0, circuitBreaker.getMetrics().getNumberOfFailedCalls());
    }

    @Test
    void shouldTripCircuitBreakerOnSystemFailuresAndFastFailWhenOpen() {
        BookingRequest request = new BookingRequest(UUID.randomUUID(), UUID.randomUUID());
        ExternalBookingException serverError = new ExternalBookingException(
                HttpStatus.INTERNAL_SERVER_ERROR,
                BookingType.HOTEL,
                ExternalBookingErrorType.SYSTEM_FAILURE,
                "Internal server error",
                "/book",
                null
        );
        when(mockDelegate.book(request)).thenThrow(serverError);

        // 2 failures exceed minimumNumberOfCalls(2) and failureRate(100% > 50%)
        assertThrows(ExternalBookingException.class, () -> client.book(request));
        assertThrows(ExternalBookingException.class, () -> client.book(request));

        // State is now OPEN!
        assertEquals(CircuitBreaker.State.OPEN, circuitBreaker.getState());
        assertEquals(2, circuitBreaker.getMetrics().getNumberOfFailedCalls());

        // Next call must fail fast without calling delegate!
        ExternalBookingException rejected = assertThrows(ExternalBookingException.class, () -> client.book(request));
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, rejected.getHttpStatus());
        assertEquals(ExternalBookingErrorType.SYSTEM_FAILURE, rejected.getErrorType());
        assertEquals("CircuitBreakerOpen", rejected.getErrorTitle());
        assertTrue(rejected.getMessage().contains("Circuit breaker is OPEN for service HOTEL"));
        assertNotNull(rejected.getUserFriendlyMessage());

        // Delegate was called only 2 times; 3rd call was intercepted by CircuitBreaker!
        verify(mockDelegate, times(2)).book(request);
    }

    @Test
    void shouldFastFailCancelWhenCircuitBreakerIsOpen() {
        circuitBreaker.transitionToOpenState();

        CancelBookingRequest request = new CancelBookingRequest(UUID.randomUUID());
        ExternalBookingException ex = assertThrows(ExternalBookingException.class, () -> client.cancel(request));

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, ex.getHttpStatus());
        assertEquals("CircuitBreakerOpen", ex.getErrorTitle());
        verify(mockDelegate, never()).cancel(any());
    }

    @Test
    void shouldTransitionToHalfOpenAndRecoverWhenDownstreamHeals() throws InterruptedException {
        BookingRequest request = new BookingRequest(UUID.randomUUID(), UUID.randomUUID());
        ExternalBookingException serverError = new ExternalBookingException(
                HttpStatus.SERVICE_UNAVAILABLE,
                BookingType.HOTEL,
                ExternalBookingErrorType.SYSTEM_FAILURE,
                "Downstream down",
                "/book",
                null
        );
        when(mockDelegate.book(request)).thenThrow(serverError);

        // Trip the circuit breaker
        assertThrows(ExternalBookingException.class, () -> client.book(request));
        assertThrows(ExternalBookingException.class, () -> client.book(request));
        assertEquals(CircuitBreaker.State.OPEN, circuitBreaker.getState());

        // Wait for open state duration to expire (500ms)
        Thread.sleep(600);

        // When downstream heals, next calls succeed
        BookingResponse healthyResponse = new BookingResponse(UUID.randomUUID(), request.bookingReference(), "CONFIRMED", Instant.now());
        doReturn(healthyResponse).when(mockDelegate).book(request);

        // Probe calls in HALF_OPEN
        BookingResponse r1 = client.book(request);
        BookingResponse r2 = client.book(request);
        assertSame(healthyResponse, r1);
        assertSame(healthyResponse, r2);

        // After successful probe calls, circuit closes again!
        assertEquals(CircuitBreaker.State.CLOSED, circuitBreaker.getState());
    }
}
