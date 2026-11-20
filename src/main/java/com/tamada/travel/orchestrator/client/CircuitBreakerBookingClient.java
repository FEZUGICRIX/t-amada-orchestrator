package com.tamada.travel.orchestrator.client;

import com.tamada.travel.orchestrator.client.dto.BookingRequest;
import com.tamada.travel.orchestrator.client.dto.BookingResponse;
import com.tamada.travel.orchestrator.client.dto.CancelBookingRequest;
import com.tamada.travel.orchestrator.client.dto.CancelBookingResponse;
import com.tamada.travel.orchestrator.exception.ExternalBookingErrorType;
import com.tamada.travel.orchestrator.exception.ExternalBookingException;
import com.tamada.travel.orchestrator.model.BookingType;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;

import java.time.Instant;
import java.util.Objects;

/**
 * Декоратор {@link BookingClient}, обеспечивающий защиту внешних вызовов с помощью Resilience4j Circuit Breaker.
 * <p>
 * Защищает оркестратор от каскадного зависания потоков при деградации или падении внешних сервисов (отелей, авиа и т.д.).
 * При переходе в состояние OPEN мгновенно прерывает вызовы (Fail Fast), не тратя ресурсы на сеть и таймауты.
 */
public class CircuitBreakerBookingClient implements BookingClient {

    private static final Logger log = LoggerFactory.getLogger(CircuitBreakerBookingClient.class);

    private final BookingClient delegate;
    private final CircuitBreaker circuitBreaker;

    public CircuitBreakerBookingClient(BookingClient delegate, CircuitBreaker circuitBreaker) {
        this.delegate = Objects.requireNonNull(delegate, "Delegate BookingClient must not be null");
        this.circuitBreaker = Objects.requireNonNull(circuitBreaker, "CircuitBreaker must not be null");
    }

    @Override
    public BookingType type() {
        return delegate.type();
    }

    @Override
    public BookingResponse book(BookingRequest request) {
        try {
            return circuitBreaker.executeSupplier(() -> delegate.book(request));
        } catch (CallNotPermittedException exception) {
            log.warn("Circuit breaker {} rejected book call: {}", circuitBreaker.getName(), exception.getMessage());
            throw toCallNotPermittedException(exception, "/book");
        }
    }

    @Override
    public CancelBookingResponse cancel(CancelBookingRequest request) {
        try {
            return circuitBreaker.executeSupplier(() -> delegate.cancel(request));
        } catch (CallNotPermittedException exception) {
            log.warn("Circuit breaker {} rejected cancel call: {}", circuitBreaker.getName(), exception.getMessage());
            throw toCallNotPermittedException(exception, "/cancel");
        }
    }

    public BookingClient getDelegate() {
        return delegate;
    }

    public CircuitBreaker getCircuitBreaker() {
        return circuitBreaker;
    }

    private ExternalBookingException toCallNotPermittedException(CallNotPermittedException exception, String path) {
        String serviceName = delegate.type().name();
        String message = "Circuit breaker is OPEN for service " + serviceName + ": " + exception.getMessage();
        String userFriendlyMessage = "Сервис " + serviceName + " временно недоступен (сработал предохранитель Circuit Breaker). Попробуйте позже.";
        return new ExternalBookingException(
                HttpStatus.SERVICE_UNAVAILABLE,
                delegate.type(),
                ExternalBookingErrorType.SYSTEM_FAILURE,
                message,
                path,
                "CircuitBreakerOpen",
                Instant.now().toString(),
                exception.getMessage(),
                userFriendlyMessage,
                exception
        );
    }
}
