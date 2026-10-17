package com.tamada.travel.orchestrator.client;

import com.tamada.travel.orchestrator.client.dto.BookingRequest;
import com.tamada.travel.orchestrator.client.dto.BookingResponse;
import com.tamada.travel.orchestrator.model.BookingType;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.time.Instant;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class RestBookingClientTest {

    @ParameterizedTest
    @MethodSource("bookingClients")
    void shouldSendBookingRequestAndMapResponse(
            BookingType type,
            String baseUrl,
            String bookingPath
    ) {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        BookingClient client = new RestBookingClient(
                type,
                builder,
                URI.create(baseUrl),
                bookingPath
        );
        UUID resourceId = UUID.randomUUID();
        UUID bookingReference = UUID.randomUUID();
        UUID externalBookingId = UUID.randomUUID();
        Instant createdAt = Instant.parse("2026-10-17T10:00:00Z");

        server.expect(requestTo(baseUrl + bookingPath))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.resourceId").value(resourceId.toString()))
                .andExpect(jsonPath("$.bookingReference").value(bookingReference.toString()))
                .andRespond(withSuccess(
                        """
                                {
                                  "id": "%s",
                                  "bookingReference": "%s",
                                  "status": "CONFIRMED",
                                  "createdAt": "%s"
                                }
                                """.formatted(
                                externalBookingId,
                                bookingReference,
                                createdAt
                        ),
                        MediaType.APPLICATION_JSON
                ));

        BookingResponse response = client.book(
                new BookingRequest(resourceId, bookingReference)
        );

        assertEquals(externalBookingId, response.id());
        assertEquals(bookingReference, response.bookingReference());
        assertEquals("CONFIRMED", response.status());
        assertEquals(createdAt, response.createdAt());
        server.verify();
    }

    private static Stream<Arguments> bookingClients() {
        return Stream.of(
                Arguments.of(
                        BookingType.FLIGHT,
                        "http://localhost:3001",
                        "/api/v1/flight/book"
                ),
                Arguments.of(
                        BookingType.HOTEL,
                        "http://localhost:3002",
                        "/api/v1/hotel/book"
                ),
                Arguments.of(
                        BookingType.INSURANCE,
                        "http://localhost:3003",
                        "/api/v1/insurance/book"
                ),
                Arguments.of(
                        BookingType.TRANSFER,
                        "http://localhost:3004",
                        "/api/v1/transfer/book"
                )
        );
    }
}
