package com.tamada.travel.orchestrator.client;

import com.tamada.travel.orchestrator.client.dto.BookingRequest;
import com.tamada.travel.orchestrator.client.dto.BookingResponse;
import com.tamada.travel.orchestrator.client.dto.CancelBookingRequest;
import com.tamada.travel.orchestrator.client.dto.CancelBookingResponse;
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
                bookingPath,
                bookingPath.replace("/book", "/cancel")
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
    void shouldSendCancelRequest() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        BookingClient client = hotelClient(builder);
        UUID bookingId = UUID.randomUUID();

        server.expect(requestTo("http://localhost:3002/api/v1/hotel/cancel"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andRespond(withSuccess(cancelResponse(bookingId), MediaType.APPLICATION_JSON));

        client.cancel(new CancelBookingRequest(bookingId));

        server.verify();
    }

    @Test
    void shouldSendExternalIdAsBookingId() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        BookingClient client = hotelClient(builder);
        UUID bookingId = UUID.randomUUID();

        server.expect(requestTo("http://localhost:3002/api/v1/hotel/cancel"))
                .andExpect(jsonPath("$.bookingId").value(bookingId.toString()))
                .andRespond(withSuccess(cancelResponse(bookingId), MediaType.APPLICATION_JSON));

        client.cancel(new CancelBookingRequest(bookingId));

        server.verify();
    }

    @ParameterizedTest
    @MethodSource("cancellationClients")
    void shouldUseCorrectCancelUrl(
            BookingType type,
            String baseUrl,
            String bookingPath,
            String cancellationPath
    ) {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        BookingClient client = new RestBookingClient(
                type,
                builder,
                URI.create(baseUrl),
                bookingPath,
                cancellationPath
        );
        UUID bookingId = UUID.randomUUID();

        server.expect(requestTo(baseUrl + cancellationPath))
                .andRespond(withSuccess(cancelResponse(bookingId), MediaType.APPLICATION_JSON));

        client.cancel(new CancelBookingRequest(bookingId));

        server.verify();
    }

    @Test
    void shouldParseSuccessfulCancelResponse() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        BookingClient client = hotelClient(builder);
        UUID bookingId = UUID.randomUUID();
        Instant updatedAt = Instant.parse("2026-10-17T11:00:00Z");

        server.expect(requestTo("http://localhost:3002/api/v1/hotel/cancel"))
                .andRespond(withSuccess(
                        """
                                {
                                  "id": "%s",
                                  "status": "CANCELLED",
                                  "updatedAt": "%s"
                                }
                                """.formatted(bookingId, updatedAt),
                        MediaType.APPLICATION_JSON
                ));

        CancelBookingResponse response = client.cancel(
                new CancelBookingRequest(bookingId)
        );

        assertEquals(bookingId, response.id());
        assertEquals("CANCELLED", response.status());
        assertEquals(updatedAt, response.updatedAt());
        server.verify();
    }

    @Test
    void shouldMapCancelNotFound() {
        ExternalBookingException exception = executeCancelErrorResponse(
                HttpStatus.NOT_FOUND,
                "Booking does not exist"
        );

        assertEquals(ExternalBookingErrorType.NOT_FOUND, exception.getErrorType());
        assertEquals("/api/v1/hotel/cancel", exception.getPath());
    }

    @Test
    void shouldMapCancelServerError() {
        ExternalBookingException exception = executeCancelErrorResponse(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "Cancellation service unavailable"
        );

        assertEquals(
                ExternalBookingErrorType.SYSTEM_FAILURE,
                exception.getErrorType()
        );
        assertTrue(exception.isRetryable());
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


    @Test
    void shouldAcceptHttp201CreatedOnBook() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        BookingClient client = hotelClient(builder);
        UUID resourceId = UUID.randomUUID();
        UUID bookingReference = UUID.randomUUID();
        UUID externalBookingId = UUID.randomUUID();
        Instant createdAt = Instant.parse("2026-10-17T10:00:00Z");

        server.expect(requestTo("http://localhost:3002/api/v1/hotel/book"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withStatus(HttpStatus.CREATED)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("""
                                {
                                  "id": "%s",
                                  "bookingReference": "%s",
                                  "status": "CONFIRMED",
                                  "createdAt": "%s",
                                  "extraUnknownField": 12345
                                }
                                """.formatted(externalBookingId, bookingReference, createdAt)));

        BookingResponse response = client.book(new BookingRequest(resourceId, bookingReference));

        assertEquals(externalBookingId, response.id());
        assertEquals(bookingReference, response.bookingReference());
        assertEquals("CONFIRMED", response.status());
        assertEquals(createdAt, response.createdAt());
        server.verify();
    }

    @Test
    void shouldAcceptHttp201CreatedOnCancel() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        BookingClient client = hotelClient(builder);
        UUID bookingId = UUID.randomUUID();
        Instant updatedAt = Instant.parse("2026-10-17T11:00:00Z");

        server.expect(requestTo("http://localhost:3002/api/v1/hotel/cancel"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withStatus(HttpStatus.CREATED)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("""
                                {
                                  "id": "%s",
                                  "status": "CANCELLED",
                                  "updatedAt": "%s",
                                  "extraMetadata": "test"
                                }
                                """.formatted(bookingId, updatedAt)));

        CancelBookingResponse response = client.cancel(new CancelBookingRequest(bookingId));

        assertEquals(bookingId, response.id());
        assertEquals("CANCELLED", response.status());
        assertEquals(updatedAt, response.updatedAt());
        server.verify();
    }

    @Test
    void shouldFallbackToRequestPathWhenErrorPathIsBlank() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        BookingClient client = hotelClient(builder);
        String bookingPath = "/api/v1/hotel/book";

        server.expect(requestTo("http://localhost:3002" + bookingPath))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("""
                                {
                                  "success": false,
                                  "statusCode": 400,
                                  "message": ["resourceId must be a UUID"],
                                  "error": "Bad Request",
                                  "timestamp": "2026-10-17T10:00:00Z",
                                  "path": ""
                                }
                                """));

        ExternalBookingException exception = assertThrows(
                ExternalBookingException.class,
                () -> client.book(new BookingRequest(UUID.randomUUID(), UUID.randomUUID()))
        );

        assertEquals(bookingPath, exception.getPath());
        assertEquals("resourceId must be a UUID", exception.getMessage());
        server.verify();
    }

    private ExternalBookingException executeCancelErrorResponse(
            HttpStatus status,
            String message
    ) {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        BookingClient client = hotelClient(builder);
        String cancellationPath = "/api/v1/hotel/cancel";

        server.expect(requestTo("http://localhost:3002" + cancellationPath))
                .andRespond(withStatus(status)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("""
                                {
                                  "success": false,
                                  "statusCode": %d,
                                  "message": "%s",
                                  "error": "%s",
                                  "timestamp": "2026-10-17T10:00:00Z",
                                  "path": "%s"
                                }
                                """.formatted(
                                status.value(),
                                message,
                                status.getReasonPhrase(),
                                cancellationPath
                        )));

        ExternalBookingException exception = assertThrows(
                ExternalBookingException.class,
                () -> client.cancel(new CancelBookingRequest(UUID.randomUUID()))
        );
        server.verify();
        return exception;
    }

    private BookingClient hotelClient(RestClient.Builder builder) {
        return new RestBookingClient(
                BookingType.HOTEL,
                builder,
                URI.create("http://localhost:3002"),
                "/api/v1/hotel/book",
                "/api/v1/hotel/cancel"
        );
    }

    private String cancelResponse(UUID bookingId) {
        return """
                {
                  "id": "%s",
                  "status": "CANCELLED",
                  "updatedAt": "2026-10-17T11:00:00Z"
                }
                """.formatted(bookingId);
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
                bookingPath,
                "/api/v1/hotel/cancel"
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

    private static Stream<Arguments> cancellationClients() {
        return Stream.of(
                Arguments.of(
                        BookingType.FLIGHT,
                        "http://localhost:3001",
                        "/api/v1/flight/book",
                        "/api/v1/flight/cancel"
                ),
                Arguments.of(
                        BookingType.HOTEL,
                        "http://localhost:3002",
                        "/api/v1/hotel/book",
                        "/api/v1/hotel/cancel"
                ),
                Arguments.of(
                        BookingType.INSURANCE,
                        "http://localhost:3003",
                        "/api/v1/insurance/book",
                        "/api/v1/insurance/cancel"
                ),
                Arguments.of(
                        BookingType.TRANSFER,
                        "http://localhost:3004",
                        "/api/v1/transfer/book",
                        "/api/v1/transfer/cancel"
                )
        );
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
