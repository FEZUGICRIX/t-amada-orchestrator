package com.tamada.travel.orchestrator.client;

import com.tamada.travel.orchestrator.client.dto.BookingRequest;
import com.tamada.travel.orchestrator.client.dto.BookingResponse;
import com.tamada.travel.orchestrator.model.BookingType;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

import java.net.URI;

public class RestBookingClient implements BookingClient {

    private final BookingType type;
    private final RestClient restClient;
    private final String bookingPath;

    public RestBookingClient(
            BookingType type,
            RestClient.Builder builder,
            URI baseUrl,
            String bookingPath
    ) {
        this.type = type;
        this.restClient = builder.baseUrl(baseUrl).build();
        this.bookingPath = bookingPath;
    }

    @Override
    public BookingType type() {
        return type;
    }

    @Override
    public BookingResponse book(BookingRequest request) {
        return restClient.post()
                .uri(bookingPath)
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .retrieve()
                .body(BookingResponse.class);
    }
}
