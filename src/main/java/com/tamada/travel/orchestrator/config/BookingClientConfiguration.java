package com.tamada.travel.orchestrator.config;

import com.tamada.travel.orchestrator.client.BookingClient;
import com.tamada.travel.orchestrator.client.RestBookingClient;
import com.tamada.travel.orchestrator.model.BookingType;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

import java.net.URI;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(BookingServicesProperties.class)
public class BookingClientConfiguration {

    @Bean
    public BookingClient flightBookingClient(
            RestClient.Builder builder,
            BookingServicesProperties properties
    ) {
        return createClient(
                BookingType.FLIGHT,
                builder,
                properties.flight().baseUrl(),
                "/api/v1/flight/book"
        );
    }

    @Bean
    public BookingClient hotelBookingClient(
            RestClient.Builder builder,
            BookingServicesProperties properties
    ) {
        return createClient(
                BookingType.HOTEL,
                builder,
                properties.hotel().baseUrl(),
                "/api/v1/hotel/book"
        );
    }

    @Bean
    public BookingClient insuranceBookingClient(
            RestClient.Builder builder,
            BookingServicesProperties properties
    ) {
        return createClient(
                BookingType.INSURANCE,
                builder,
                properties.insurance().baseUrl(),
                "/api/v1/insurance/book"
        );
    }

    @Bean
    public BookingClient transferBookingClient(
            RestClient.Builder builder,
            BookingServicesProperties properties
    ) {
        return createClient(
                BookingType.TRANSFER,
                builder,
                properties.transfer().baseUrl(),
                "/api/v1/transfer/book"
        );
    }

    private BookingClient createClient(
            BookingType type,
            RestClient.Builder builder,
            URI baseUrl,
            String bookingPath
    ) {
        return new RestBookingClient(
                type,
                builder.clone(),
                baseUrl,
                bookingPath
        );
    }
}
