package com.tamada.travel.orchestrator.client;

import com.tamada.travel.orchestrator.client.dto.BookingRequest;
import com.tamada.travel.orchestrator.client.dto.BookingResponse;
import com.tamada.travel.orchestrator.client.dto.CancelBookingRequest;
import com.tamada.travel.orchestrator.client.dto.CancelBookingResponse;
import com.tamada.travel.orchestrator.config.BookingServicesProperties;
import com.tamada.travel.orchestrator.exception.ExternalBookingException;
import com.tamada.travel.orchestrator.model.BookingType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Декоратор {@link BookingClient}, обеспечивающий отказоустойчивые повторные попытки (Retry)
 * с настраиваемой экспоненциальной задержкой (Exponential Backoff) и случайным разбросом (Jitter).
 */
public class RetryingBookingClient implements BookingClient {

    private static final Logger log = LoggerFactory.getLogger(RetryingBookingClient.class);

    private final BookingClient delegate;
    private final BookingServicesProperties.RetryProperties retryProperties;
    private final Sleeper sleeper;
    private final JitterStrategy jitterStrategy;
    private final com.tamada.travel.orchestrator.observability.SagaMetrics metrics;

    public RetryingBookingClient(
            BookingClient delegate,
            BookingServicesProperties.RetryProperties retryProperties
    ) {
        this(delegate, retryProperties, Sleeper.DEFAULT);
    }

    public RetryingBookingClient(
            BookingClient delegate,
            BookingServicesProperties.RetryProperties retryProperties,
            Sleeper sleeper
    ) {
        this(
                delegate,
                retryProperties,
                sleeper,
                (retryProperties != null && retryProperties.jitterEnabled())
                        ? JitterStrategy.FULL_JITTER
                        : JitterStrategy.NO_JITTER
        );
    }

    public RetryingBookingClient(
            BookingClient delegate,
            BookingServicesProperties.RetryProperties retryProperties,
            Sleeper sleeper,
            JitterStrategy jitterStrategy
    ) {
        this(delegate, retryProperties, sleeper, jitterStrategy, null);
    }

    public RetryingBookingClient(
            BookingClient delegate,
            BookingServicesProperties.RetryProperties retryProperties,
            Sleeper sleeper,
            JitterStrategy jitterStrategy,
            com.tamada.travel.orchestrator.observability.SagaMetrics metrics
    ) {
        this.delegate = Objects.requireNonNull(delegate, "Delegate BookingClient must not be null");
        this.retryProperties = retryProperties != null
                ? retryProperties
                : new BookingServicesProperties.RetryProperties(3, Duration.ofMillis(200), 2.0, Duration.ofSeconds(2), false);
        this.sleeper = Objects.requireNonNull(sleeper, "Sleeper must not be null");
        this.jitterStrategy = jitterStrategy != null ? jitterStrategy : JitterStrategy.NO_JITTER;
        this.metrics = metrics;
    }

    @Override
    public BookingType type() {
        return delegate.type();
    }

    @Override
    public BookingResponse book(BookingRequest request) {
        return executeWithRetry("book", () -> delegate.book(request));
    }

    @Override
    public CancelBookingResponse cancel(CancelBookingRequest request) {
        return executeWithRetry("cancel", () -> delegate.cancel(request));
    }

    public JitterStrategy getJitterStrategy() {
        return jitterStrategy;
    }

    public BookingClient getDelegate() {
        return delegate;
    }

    private <T> T executeWithRetry(String operation, Supplier<T> action) {
        int maxAttempts = retryProperties.maxAttempts();
        Duration delay = retryProperties.initialDelay();
        double multiplier = retryProperties.multiplier();
        Duration maxDelay = retryProperties.maxDelay();

        int attempt = 1;
        while (true) {
            try {
                return action.get();
            } catch (ExternalBookingException exception) {
                if (!exception.isRetryable() || attempt >= maxAttempts) {
                    if (attempt >= maxAttempts && exception.isRetryable()) {
                        log.warn(
                                "Exhausted all {} retry attempts for {} operation on service {}: {}",
                                maxAttempts,
                                operation,
                                delegate.type(),
                                exception.getMessage()
                        );
                    }
                    throw exception;
                }

                Duration actualDelay = jitterStrategy.computeDelay(delay);

                log.warn(
                        "Attempt {}/{} failed for {} on service {}: {}. Retrying in {}ms (jittered from {}ms)...",
                        attempt,
                        maxAttempts,
                        operation,
                        delegate.type(),
                        exception.getMessage(),
                        actualDelay.toMillis(),
                        delay.toMillis()
                );

                if (metrics != null) {
                    metrics.incrementStepRetry(delegate.type(), operation);
                }

                try {
                    sleeper.sleep(actualDelay);
                } catch (InterruptedException interruptedException) {
                    Thread.currentThread().interrupt();
                    throw new ExternalBookingException(
                            exception.getHttpStatus(),
                            exception.getService(),
                            exception.getErrorType(),
                            "Retry execution interrupted: " + interruptedException.getMessage(),
                            exception.getPath(),
                            interruptedException
                    );
                }

                long nextDelayMillis = Math.min(
                        (long) (delay.toMillis() * multiplier),
                        maxDelay.toMillis()
                );
                delay = Duration.ofMillis(nextDelayMillis);
                attempt++;
            }
        }
    }
}
