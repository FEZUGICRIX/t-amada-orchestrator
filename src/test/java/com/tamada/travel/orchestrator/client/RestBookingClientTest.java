package com.tamada.travel.orchestrator.client;

import com.tamada.travel.orchestrator.client.dto.BookingRequest;
import com.tamada.travel.orchestrator.client.dto.BookingResponse;
import com.tamada.travel.orchestrator.exception.ExternalBookingErrorType;
import com.tamada.travel.orchestrator.exception.ExternalBookingException;
import com.tamada.travel.orchestrator.model.BookingType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.time.Instant;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
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

    @Test
    void shouldMapBadRequest() {
        ExternalBookingException exception = executeErrorResponse(
                HttpStatus.BAD_REQUEST,
                "\"Invalid booking request\""
        );

        assertEquals(ExternalBookingErrorType.VALIDATION_ERROR, exception.getErrorType());
        assertEquals(HttpStatus.BAD_REQUEST, exception.getHttpStatus());
        assertFalse(exception.isRetryable());
    }

    @Test
    void shouldParseValidationMessageArray() {
        ExternalBookingException exception = executeErrorResponse(
                HttpStatus.BAD_REQUEST,
                "[\"resourceId must be a UUID\","
                        + "\"bookingReference should not be empty\"]"
        );

        assertEquals(
                "resourceId must be a UUID; bookingReference should not be empty",
                exception.getMessage()
        );
    }

    @Test
    void shouldMapNotFound() {
        ExternalBookingException exception = executeErrorResponse(
                HttpStatus.NOT_FOUND,
                "\"Resource does not exist\""
        );

        assertEquals(ExternalBookingErrorType.NOT_FOUND, exception.getErrorType());
        assertFalse(exception.isRetryable());
    }

    @Test
    void shouldMapConflict() {
        ExternalBookingException exception = executeErrorResponse(
                HttpStatus.CONFLICT,
                "\"Hotel room is already occupied or unavailable\""
        );

        assertEquals(
                ExternalBookingErrorType.RESOURCE_UNAVAILABLE,
                exception.getErrorType()
        );
        assertEquals(BookingType.HOTEL, exception.getService());
        assertEquals("/api/v1/hotel/book", exception.getPath());
        assertFalse(exception.isRetryable());
    }

    @Test
    void shouldMapInternalServerError() {
        ExternalBookingException exception = executeErrorResponse(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "\"External database unavailable\""
        );

        assertEquals(
                ExternalBookingErrorType.SYSTEM_FAILURE,
                exception.getErrorType()
        );
        assertTrue(exception.isRetryable());
    }

    @Test
    void shouldPreserveExternalErrorMessage() {
        ExternalBookingException exception = executeErrorResponse(
                HttpStatus.CONFLICT,
                "\"Hotel room is already occupied or unavailable\""
        );

        assertEquals(
                "Hotel room is already occupied or unavailable",
                exception.getMessage()
        );
    }

    private ExternalBookingException executeErrorResponse(
            HttpStatus status,
            String messageJson
    ) {
        String baseUrl = "http://localhost:3002";
        String bookingPath = "/api/v1/hotel/book";
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        BookingClient client = new RestBookingClient(
                BookingType.HOTEL,
                builder,
                URI.create(baseUrl),
                bookingPath
        );

        server.expect(requestTo(baseUrl + bookingPath))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withStatus(status)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("""
                                {
                                  "success": false,
                                  "statusCode": %d,
                                  "message": %s,
                                  "error": "%s",
                                  "timestamp": "2026-10-17T10:00:00Z",
                                  "path": "%s"
                                }
                                """.formatted(
                                status.value(),
                                messageJson,
                                status.getReasonPhrase(),
                                bookingPath
                        )));

        ExternalBookingException exception = assertThrows(
                ExternalBookingException.class,
                () -> client.book(new BookingRequest(
                        UUID.randomUUID(),
                        UUID.randomUUID()
                ))
        );
        server.verify();
        return exception;
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
