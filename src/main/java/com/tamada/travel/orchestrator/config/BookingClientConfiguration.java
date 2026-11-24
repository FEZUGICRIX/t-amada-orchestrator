package com.tamada.travel.orchestrator.config;

import com.tamada.travel.orchestrator.client.BookingClient;
import com.tamada.travel.orchestrator.client.CircuitBreakerBookingClient;
import com.tamada.travel.orchestrator.client.RestBookingClient;
import com.tamada.travel.orchestrator.client.RetryingBookingClient;
import com.tamada.travel.orchestrator.client.Sleeper;
import com.tamada.travel.orchestrator.exception.ExternalBookingException;
import com.tamada.travel.orchestrator.model.BookingType;
import com.tamada.travel.orchestrator.observability.CorrelationIdClientInterceptor;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(BookingServicesProperties.class)
public class BookingClientConfiguration {

    private static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration DEFAULT_READ_TIMEOUT = Duration.ofSeconds(10);

    @Bean
    @ConditionalOnMissingBean
    public RestClient.Builder restClientBuilder(
            BookingServicesProperties properties,
            ObjectProvider<CorrelationIdClientInterceptor> correlationIdInterceptorProvider
    ) {
        Duration connectTimeout = properties.connectTimeout() != null
                ? properties.connectTimeout()
                : DEFAULT_CONNECT_TIMEOUT;
        Duration readTimeout = properties.readTimeout() != null
                ? properties.readTimeout()
                : DEFAULT_READ_TIMEOUT;

        HttpClient httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_2)
                .connectTimeout(connectTimeout)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();

        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(readTimeout);

        RestClient.Builder builder = RestClient.builder()
                .requestFactory(requestFactory);

        CorrelationIdClientInterceptor interceptor = correlationIdInterceptorProvider.getIfAvailable(CorrelationIdClientInterceptor::new);
        builder.requestInterceptor(interceptor);

        return builder;
    }

    @Bean
    @ConditionalOnMissingBean
    public Sleeper bookingClientSleeper() {
        return Sleeper.DEFAULT;
    }

    @Bean
    @ConditionalOnMissingBean
    public CircuitBreakerRegistry circuitBreakerRegistry(BookingServicesProperties properties) {
        BookingServicesProperties.CircuitBreakerProperties cbProps = properties.circuitBreaker();
        CircuitBreakerConfig config = CircuitBreakerConfig.custom()
                .failureRateThreshold(cbProps.failureRateThreshold())
                .slidingWindowSize(cbProps.slidingWindowSize())
                .minimumNumberOfCalls(cbProps.minimumNumberOfCalls())
                .waitDurationInOpenState(cbProps.waitDurationInOpenState())
                .permittedNumberOfCallsInHalfOpenState(cbProps.permittedNumberOfCallsInHalfOpenState())
                .recordException(throwable -> {
                    if (throwable instanceof ExternalBookingException ex) {
                        return ex.isRetryable();
                    }
                    return true;
                })
                .ignoreException(throwable -> throwable instanceof ExternalBookingException ex && !ex.isRetryable())
                .build();

        return CircuitBreakerRegistry.of(config);
    }

    @Bean
    public BookingClient flightBookingClient(
            RestClient.Builder builder,
            BookingServicesProperties properties,
            Sleeper sleeper,
            CircuitBreakerRegistry circuitBreakerRegistry,
            ObjectProvider<com.tamada.travel.orchestrator.observability.SagaMetrics> sagaMetricsProvider) {
        return createClient(
                BookingType.FLIGHT,
                builder,
                properties.flight().baseUrl(),
                "/api/v1/flight/book",
                "/api/v1/flight/cancel",
                properties,
                sleeper,
                circuitBreakerRegistry,
                sagaMetricsProvider.getIfAvailable());
    }

    @Bean
    public BookingClient hotelBookingClient(
            RestClient.Builder builder,
            BookingServicesProperties properties,
            Sleeper sleeper,
            CircuitBreakerRegistry circuitBreakerRegistry,
            ObjectProvider<com.tamada.travel.orchestrator.observability.SagaMetrics> sagaMetricsProvider) {
        return createClient(
                BookingType.HOTEL,
                builder,
                properties.hotel().baseUrl(),
                "/api/v1/hotel/book",
                "/api/v1/hotel/cancel",
                properties,
                sleeper,
                circuitBreakerRegistry,
                sagaMetricsProvider.getIfAvailable());
    }

    @Bean
    public BookingClient insuranceBookingClient(
            RestClient.Builder builder,
            BookingServicesProperties properties,
            Sleeper sleeper,
            CircuitBreakerRegistry circuitBreakerRegistry,
            ObjectProvider<com.tamada.travel.orchestrator.observability.SagaMetrics> sagaMetricsProvider) {
        return createClient(
                BookingType.INSURANCE,
                builder,
                properties.insurance().baseUrl(),
                "/api/v1/insurance/book",
                "/api/v1/insurance/cancel",
                properties,
                sleeper,
                circuitBreakerRegistry,
                sagaMetricsProvider.getIfAvailable());
    }

    @Bean
    public BookingClient transferBookingClient(
            RestClient.Builder builder,
            BookingServicesProperties properties,
            Sleeper sleeper,
            CircuitBreakerRegistry circuitBreakerRegistry,
            ObjectProvider<com.tamada.travel.orchestrator.observability.SagaMetrics> sagaMetricsProvider) {
        return createClient(
                BookingType.TRANSFER,
                builder,
                properties.transfer().baseUrl(),
                "/api/v1/transfer/book",
                "/api/v1/transfer/cancel",
                properties,
                sleeper,
                circuitBreakerRegistry,
                sagaMetricsProvider.getIfAvailable());
    }

    private BookingClient createClient(
            BookingType type,
            RestClient.Builder builder,
            URI baseUrl,
            String bookingPath,
            String cancellationPath,
            BookingServicesProperties properties,
            Sleeper sleeper,
            CircuitBreakerRegistry circuitBreakerRegistry,
            com.tamada.travel.orchestrator.observability.SagaMetrics metrics) {
        BookingClient restClient = new RestBookingClient(
                type,
                builder.clone(),
                normalizeBaseUrl(baseUrl),
                bookingPath,
                cancellationPath);

        BookingClient targetClient = new RetryingBookingClient(
                restClient,
                properties.retry(),
                sleeper,
                (properties.retry() != null && properties.retry().jitterEnabled()) ? com.tamada.travel.orchestrator.client.JitterStrategy.FULL_JITTER : com.tamada.travel.orchestrator.client.JitterStrategy.NO_JITTER,
                metrics);

        if (properties.circuitBreaker() != null && properties.circuitBreaker().enabled() && circuitBreakerRegistry != null) {
            CircuitBreaker circuitBreaker = circuitBreakerRegistry.circuitBreaker(type.name());
            targetClient = new CircuitBreakerBookingClient(targetClient, circuitBreaker);
        }

        return targetClient;
    }

    private static URI normalizeBaseUrl(URI baseUrl) {
        if (baseUrl == null) {
            return null;
        }
        String str = baseUrl.toString();
        if (str.endsWith("/")) {
            return URI.create(str.substring(0, str.length() - 1));
        }
        return baseUrl;
    }
}
