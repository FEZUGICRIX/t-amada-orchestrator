package com.tamada.travel.orchestrator.client;

import com.tamada.travel.orchestrator.client.dto.BookingRequest;
import com.tamada.travel.orchestrator.client.dto.BookingResponse;
import com.tamada.travel.orchestrator.client.dto.CancelBookingRequest;
import com.tamada.travel.orchestrator.client.dto.CancelBookingResponse;
import com.tamada.travel.orchestrator.config.BookingServicesProperties;
import com.tamada.travel.orchestrator.exception.ExternalBookingErrorType;
import com.tamada.travel.orchestrator.exception.ExternalBookingException;
import com.tamada.travel.orchestrator.model.BookingType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RetryingBookingClientTest {

    private BookingClient mockDelegate;
    private List<Duration> recordedDelays;
    private Sleeper recordingSleeper;
    private BookingServicesProperties.RetryProperties retryProperties;

    @BeforeEach
    void setUp() {
        mockDelegate = mock(BookingClient.class);
        when(mockDelegate.type()).thenReturn(BookingType.HOTEL);
        recordedDelays = new ArrayList<>();
        recordingSleeper = recordedDelays::add;
        retryProperties = new BookingServicesProperties.RetryProperties(
                3,
                Duration.ofMillis(100),
                2.0,
                Duration.ofMillis(500)
        );
    }

    @Test
    void shouldReturnResponseImmediatelyOnFirstSuccessfulBooking() {
        RetryingBookingClient retryingClient = new RetryingBookingClient(
                mockDelegate,
                retryProperties,
                recordingSleeper
        );

        BookingRequest request = new BookingRequest(UUID.randomUUID(), UUID.randomUUID());
        BookingResponse expectedResponse = new BookingResponse(
                UUID.randomUUID(),
                request.bookingReference(),
                "CONFIRMED",
                Instant.now()
        );
        when(mockDelegate.book(request)).thenReturn(expectedResponse);

        BookingResponse actualResponse = retryingClient.book(request);

        assertEquals(expectedResponse, actualResponse);
        verify(mockDelegate, times(1)).book(request);
        assertTrue(recordedDelays.isEmpty());
    }

    @Test
    void shouldRetryAndSucceedOnSecondAttemptAfterTimeout() {
        RetryingBookingClient retryingClient = new RetryingBookingClient(
                mockDelegate,
                retryProperties,
                recordingSleeper
        );

        BookingRequest request = new BookingRequest(UUID.randomUUID(), UUID.randomUUID());
        BookingResponse expectedResponse = new BookingResponse(
                UUID.randomUUID(),
                request.bookingReference(),
                "CONFIRMED",
                Instant.now()
        );

        ExternalBookingException timeoutException = new ExternalBookingException(
                HttpStatus.GATEWAY_TIMEOUT,
                BookingType.HOTEL,
                ExternalBookingErrorType.SYSTEM_FAILURE,
                "Connection timed out",
                "/api/v1/hotel/book",
                null
        );

        when(mockDelegate.book(request))
                .thenThrow(timeoutException)
                .thenReturn(expectedResponse);

        BookingResponse actualResponse = retryingClient.book(request);

        assertEquals(expectedResponse, actualResponse);
        verify(mockDelegate, times(2)).book(request);
        assertEquals(1, recordedDelays.size());
        assertEquals(Duration.ofMillis(100), recordedDelays.get(0));
    }

    @Test
    void shouldRetryAndSucceedOnThirdAttemptAfter500Errors() {
        RetryingBookingClient retryingClient = new RetryingBookingClient(
                mockDelegate,
                retryProperties,
                recordingSleeper
        );

        BookingRequest request = new BookingRequest(UUID.randomUUID(), UUID.randomUUID());
        BookingResponse expectedResponse = new BookingResponse(
                UUID.randomUUID(),
                request.bookingReference(),
                "CONFIRMED",
                Instant.now()
        );

        ExternalBookingException serverError = new ExternalBookingException(
                HttpStatus.INTERNAL_SERVER_ERROR,
                BookingType.HOTEL,
                ExternalBookingErrorType.SYSTEM_FAILURE,
                "Internal Server Error",
                "/api/v1/hotel/book",
                null
        );

        when(mockDelegate.book(request))
                .thenThrow(serverError)
                .thenThrow(serverError)
                .thenReturn(expectedResponse);

        BookingResponse actualResponse = retryingClient.book(request);

        assertEquals(expectedResponse, actualResponse);
        verify(mockDelegate, times(3)).book(request);
        assertEquals(2, recordedDelays.size());
        assertEquals(Duration.ofMillis(100), recordedDelays.get(0));
        assertEquals(Duration.ofMillis(200), recordedDelays.get(1));
    }

    @Test
    void shouldExhaustRetriesAndThrowExceptionAfterMaxAttempts() {
        RetryingBookingClient retryingClient = new RetryingBookingClient(
                mockDelegate,
                retryProperties,
                recordingSleeper
        );

        BookingRequest request = new BookingRequest(UUID.randomUUID(), UUID.randomUUID());
        ExternalBookingException timeoutException = new ExternalBookingException(
                HttpStatus.GATEWAY_TIMEOUT,
                BookingType.HOTEL,
                ExternalBookingErrorType.SYSTEM_FAILURE,
                "Timed out",
                "/api/v1/hotel/book",
                null
        );

        when(mockDelegate.book(request)).thenThrow(timeoutException);

        ExternalBookingException thrown = assertThrows(
                ExternalBookingException.class,
                () -> retryingClient.book(request)
        );

        assertEquals(timeoutException, thrown);
        verify(mockDelegate, times(3)).book(request);
        assertEquals(2, recordedDelays.size());
        assertEquals(Duration.ofMillis(100), recordedDelays.get(0));
        assertEquals(Duration.ofMillis(200), recordedDelays.get(1));
    }

    @Test
    void shouldNotRetryNonRetryableErrors() {
        RetryingBookingClient retryingClient = new RetryingBookingClient(
                mockDelegate,
                retryProperties,
                recordingSleeper
        );

        BookingRequest request = new BookingRequest(UUID.randomUUID(), UUID.randomUUID());
        ExternalBookingException conflictException = new ExternalBookingException(
                HttpStatus.CONFLICT,
                BookingType.HOTEL,
                ExternalBookingErrorType.RESOURCE_UNAVAILABLE,
                "Resource unavailable",
                "/api/v1/hotel/book",
                null
        );

        when(mockDelegate.book(request)).thenThrow(conflictException);

        ExternalBookingException thrown = assertThrows(
                ExternalBookingException.class,
                () -> retryingClient.book(request)
        );

        assertEquals(conflictException, thrown);
        verify(mockDelegate, times(1)).book(request);
        assertTrue(recordedDelays.isEmpty());
    }

    @Test
    void shouldRetryCancellationAndSucceed() {
        RetryingBookingClient retryingClient = new RetryingBookingClient(
                mockDelegate,
                retryProperties,
                recordingSleeper
        );

        UUID bookingId = UUID.randomUUID();
        CancelBookingRequest request = new CancelBookingRequest(bookingId);
        CancelBookingResponse expectedResponse = new CancelBookingResponse(
                bookingId,
                "CANCELLED",
                Instant.now()
        );

        ExternalBookingException serverError = new ExternalBookingException(
                HttpStatus.INTERNAL_SERVER_ERROR,
                BookingType.HOTEL,
                ExternalBookingErrorType.SYSTEM_FAILURE,
                "Service unavailable",
                "/api/v1/hotel/cancel",
                null
        );

        when(mockDelegate.cancel(request))
                .thenThrow(serverError)
                .thenReturn(expectedResponse);

        CancelBookingResponse actualResponse = retryingClient.cancel(request);

        assertEquals(expectedResponse, actualResponse);
        verify(mockDelegate, times(2)).cancel(request);
        assertEquals(1, recordedDelays.size());
    }

    @Test
    void shouldCapDelayAtMaxDelay() {
        BookingServicesProperties.RetryProperties customRetry = new BookingServicesProperties.RetryProperties(
                5,
                Duration.ofMillis(100),
                4.0,
                Duration.ofMillis(250)
        );

        RetryingBookingClient retryingClient = new RetryingBookingClient(
                mockDelegate,
                customRetry,
                recordingSleeper
        );

        BookingRequest request = new BookingRequest(UUID.randomUUID(), UUID.randomUUID());
        ExternalBookingException error = new ExternalBookingException(
                HttpStatus.SERVICE_UNAVAILABLE,
                BookingType.HOTEL,
                ExternalBookingErrorType.SYSTEM_FAILURE,
                "503",
                "/api/v1/hotel/book",
                null
        );

        when(mockDelegate.book(request)).thenThrow(error);

        assertThrows(ExternalBookingException.class, () -> retryingClient.book(request));
        verify(mockDelegate, times(5)).book(request);
        assertEquals(4, recordedDelays.size());
        assertEquals(Duration.ofMillis(100), recordedDelays.get(0));
        assertEquals(Duration.ofMillis(250), recordedDelays.get(1));
        assertEquals(Duration.ofMillis(250), recordedDelays.get(2));
        assertEquals(Duration.ofMillis(250), recordedDelays.get(3));
    }

    @Test
    void shouldHandleThreadInterruptionGracefully() {
        Sleeper interruptingSleeper = duration -> {
            throw new InterruptedException("Thread interrupted during retry sleep");
        };

        RetryingBookingClient retryingClient = new RetryingBookingClient(
                mockDelegate,
                retryProperties,
                interruptingSleeper
        );

        BookingRequest request = new BookingRequest(UUID.randomUUID(), UUID.randomUUID());
        ExternalBookingException error = new ExternalBookingException(
                HttpStatus.GATEWAY_TIMEOUT,
                BookingType.HOTEL,
                ExternalBookingErrorType.SYSTEM_FAILURE,
                "Timeout",
                "/api/v1/hotel/book",
                null
        );

        when(mockDelegate.book(request)).thenThrow(error);

        ExternalBookingException thrown = assertThrows(
                ExternalBookingException.class,
                () -> retryingClient.book(request)
        );

        assertTrue(thrown.getMessage().contains("interrupted"));
        assertTrue(Thread.currentThread().isInterrupted());
        // Clean interrupted flag
        Thread.interrupted();
    }

    @Test
    void shouldApplyJitterStrategyWhenConfigured() {
        BookingServicesProperties.RetryProperties jitterProps = new BookingServicesProperties.RetryProperties(
                3,
                Duration.ofMillis(100),
                2.0,
                Duration.ofMillis(500),
                true
        );

        RetryingBookingClient retryingClient = new RetryingBookingClient(
                mockDelegate,
                jitterProps,
                recordingSleeper
        );

        BookingRequest request = new BookingRequest(UUID.randomUUID(), UUID.randomUUID());
        ExternalBookingException timeoutException = new ExternalBookingException(
                HttpStatus.GATEWAY_TIMEOUT,
                BookingType.HOTEL,
                ExternalBookingErrorType.SYSTEM_FAILURE,
                "Connection timed out",
                "/api/v1/hotel/book",
                null
        );

        when(mockDelegate.book(request))
                .thenThrow(timeoutException)
                .thenReturn(new BookingResponse(UUID.randomUUID(), request.bookingReference(), "CONFIRMED", Instant.now()));

        retryingClient.book(request);

        assertEquals(1, recordedDelays.size());
        Duration jitteredDelay = recordedDelays.get(0);
        // Full jitter should be between 50ms and 100ms
        assertTrue(jitteredDelay.toMillis() >= 50 && jitteredDelay.toMillis() <= 100,
                "Expected jittered delay between 50 and 100 ms, but was: " + jitteredDelay.toMillis());
    }

    @Test
    void shouldUseCustomJitterStrategy() {
        JitterStrategy fixedJitter = delay -> delay.plusMillis(42);
        RetryingBookingClient retryingClient = new RetryingBookingClient(
                mockDelegate,
                retryProperties,
                recordingSleeper,
                fixedJitter
        );

        BookingRequest request = new BookingRequest(UUID.randomUUID(), UUID.randomUUID());
        ExternalBookingException timeoutException = new ExternalBookingException(
                HttpStatus.GATEWAY_TIMEOUT,
                BookingType.HOTEL,
                ExternalBookingErrorType.SYSTEM_FAILURE,
                "Timeout",
                "/api/v1/hotel/book",
                null
        );

        when(mockDelegate.book(request))
                .thenThrow(timeoutException)
                .thenReturn(new BookingResponse(UUID.randomUUID(), request.bookingReference(), "CONFIRMED", Instant.now()));

        retryingClient.book(request);

        assertEquals(1, recordedDelays.size());
        assertEquals(Duration.ofMillis(142), recordedDelays.get(0));
    }
}
