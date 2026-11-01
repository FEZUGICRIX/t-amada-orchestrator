package com.tamada.travel.orchestrator.config;

import com.tamada.travel.orchestrator.client.BookingClient;
import com.tamada.travel.orchestrator.client.RestBookingClient;
import com.tamada.travel.orchestrator.model.BookingType;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.time.Duration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(BookingServicesProperties.class)
public class BookingClientConfiguration {

    private static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration DEFAULT_READ_TIMEOUT = Duration.ofSeconds(10);

    @Bean
    @ConditionalOnMissingBean
    public RestClient.Builder restClientBuilder(BookingServicesProperties properties) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        Duration connectTimeout = properties.connectTimeout() != null
                ? properties.connectTimeout()
                : DEFAULT_CONNECT_TIMEOUT;
        Duration readTimeout = properties.readTimeout() != null
                ? properties.readTimeout()
                : DEFAULT_READ_TIMEOUT;
        requestFactory.setConnectTimeout(connectTimeout);
        requestFactory.setReadTimeout(readTimeout);

        return RestClient.builder()
                .requestFactory(requestFactory);
    }

    @Bean
    public BookingClient flightBookingClient(
            RestClient.Builder builder,
            BookingServicesProperties properties) {
        return createClient(
                BookingType.FLIGHT,
                builder,
                properties.flight().baseUrl(),
                "/api/v1/flight/book",
                "/api/v1/flight/cancel");
    }

    @Bean
    public BookingClient hotelBookingClient(
            RestClient.Builder builder,
            BookingServicesProperties properties) {
        return createClient(
                BookingType.HOTEL,
                builder,
                properties.hotel().baseUrl(),
                "/api/v1/hotel/book",
                "/api/v1/hotel/cancel");
    }

    @Bean
    public BookingClient insuranceBookingClient(
            RestClient.Builder builder,
            BookingServicesProperties properties) {
        return createClient(
                BookingType.INSURANCE,
                builder,
                properties.insurance().baseUrl(),
                "/api/v1/insurance/book",
                "/api/v1/insurance/cancel");
    }

    @Bean
    public BookingClient transferBookingClient(
            RestClient.Builder builder,
            BookingServicesProperties properties) {
        return createClient(
                BookingType.TRANSFER,
                builder,
                properties.transfer().baseUrl(),
                "/api/v1/transfer/book",
                "/api/v1/transfer/cancel");
    }

    private BookingClient createClient(
            BookingType type,
            RestClient.Builder builder,
            URI baseUrl,
            String bookingPath,
            String cancellationPath) {
        return new RestBookingClient(
                type,
                builder.clone(),
                normalizeBaseUrl(baseUrl),
                bookingPath,
                cancellationPath);
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
