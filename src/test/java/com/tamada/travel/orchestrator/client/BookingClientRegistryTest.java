package com.tamada.travel.orchestrator.client;

import com.tamada.travel.orchestrator.client.dto.BookingRequest;
import com.tamada.travel.orchestrator.client.dto.BookingResponse;
import com.tamada.travel.orchestrator.model.BookingType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertSame;

class BookingClientRegistryTest {

    @Test
    void shouldReturnClientForEveryBookingType() {
        BookingClient flightClient = new StubBookingClient(BookingType.FLIGHT);
        BookingClient hotelClient = new StubBookingClient(BookingType.HOTEL);
        BookingClient insuranceClient = new StubBookingClient(BookingType.INSURANCE);
        BookingClient transferClient = new StubBookingClient(BookingType.TRANSFER);
        BookingClientRegistry registry = new BookingClientRegistry(List.of(
                flightClient,
                hotelClient,
                insuranceClient,
                transferClient
        ));

        assertSame(flightClient, registry.getClient(BookingType.FLIGHT));
        assertSame(hotelClient, registry.getClient(BookingType.HOTEL));
        assertSame(insuranceClient, registry.getClient(BookingType.INSURANCE));
        assertSame(transferClient, registry.getClient(BookingType.TRANSFER));
    }

    private record StubBookingClient(BookingType type) implements BookingClient {

        @Override
        public BookingResponse book(BookingRequest request) {
            throw new UnsupportedOperationException();
        }
    }
}
