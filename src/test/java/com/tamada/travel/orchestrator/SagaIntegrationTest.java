package com.tamada.travel.orchestrator;

import com.tamada.travel.orchestrator.client.BookingClient;
import com.tamada.travel.orchestrator.client.BookingClientRegistry;
import com.tamada.travel.orchestrator.client.RestBookingClient;
import com.tamada.travel.orchestrator.controller.CompositeOrderController;
import com.tamada.travel.orchestrator.exception.ExternalBookingErrorType;
import com.tamada.travel.orchestrator.exception.ExternalBookingException;
import com.tamada.travel.orchestrator.exception.GlobalExceptionHandler;
import com.tamada.travel.orchestrator.model.BookingStatus;
import com.tamada.travel.orchestrator.model.BookingStep;
import com.tamada.travel.orchestrator.model.BookingType;
import com.tamada.travel.orchestrator.model.CompositeOrder;
import com.tamada.travel.orchestrator.model.OrderStatus;
import com.tamada.travel.orchestrator.repository.CompositeOrderRepository;
import com.tamada.travel.orchestrator.service.BookingOrchestrationService;
import com.tamada.travel.orchestrator.service.CompositeOrderService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;

import java.lang.reflect.Proxy;
import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class SagaIntegrationTest {

    private final UUID orderId = UUID.randomUUID();
    private final Map<BookingType, ExternalServiceMock> services = new EnumMap<>(BookingType.class);
    private final List<String> externalCalls = new ArrayList<>();

    private CompositeOrder storedOrder;
    private CompositeOrderService orderService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        List<BookingClient> clients = new ArrayList<>();
        for (BookingType type : BookingType.values()) {
            String serviceName = type.name().toLowerCase();
            String baseUrl = "http://" + serviceName + ".test";
            RestClient.Builder builder = RestClient.builder();
            MockRestServiceServer server = MockRestServiceServer
                    .bindTo(builder)
                    .build();
            clients.add(new RestBookingClient(
                    type,
                    builder,
                    URI.create(baseUrl),
                    "/api/v1/" + serviceName + "/book",
                    "/api/v1/" + serviceName + "/cancel"));
            services.put(type, new ExternalServiceMock(baseUrl, server));
        }

        BookingOrchestrationService orchestrationService = new BookingOrchestrationService(
                createRepository(),
                new BookingClientRegistry(clients),
                new TransactionTemplate(new NoOpTransactionManager()));
        orderService = new CompositeOrderService(
                createRepository(),
                orchestrationService);
        CompositeOrderController controller = new CompositeOrderController(orderService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void shouldCompleteSuccessfulSaga() throws Exception {
        CompositeOrder order = createOrder(
                BookingType.FLIGHT,
                BookingType.HOTEL,
                BookingType.INSURANCE,
                BookingType.TRANSFER);
        Map<BookingType, UUID> externalIds = new EnumMap<>(BookingType.class);
        for (BookingStep step : order.getBookingSteps()) {
            UUID externalId = UUID.randomUUID();
            externalIds.put(step.getType(), externalId);
            expectBookSuccess(step, externalId);
        }

        mockMvc.perform(post("/api/v1/orders/{id}/start", orderId))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .jsonPath("$.status").value("COMPLETED"));

        assertEquals(OrderStatus.COMPLETED, order.getStatus());
        assertEquals(
                List.of(
                        "FLIGHT book",
                        "HOTEL book",
                        "INSURANCE book",
                        "TRANSFER book"),
                externalCalls);
        for (BookingStep step : order.getBookingSteps()) {
            assertEquals(BookingStatus.CONFIRMED, step.getStatus());
            assertEquals(
                    externalIds.get(step.getType()).toString(),
                    step.getExternalId());
        }
        verifyExternalServices();
    }

    @Test
    void shouldCompensateAfterSecondStepFailure() throws Exception {
        CompositeOrder order = createOrder(
                BookingType.FLIGHT,
                BookingType.HOTEL,
                BookingType.INSURANCE,
                BookingType.TRANSFER);
        BookingStep flight = order.getBookingSteps().get(0);
        BookingStep hotel = order.getBookingSteps().get(1);
        UUID flightExternalId = UUID.randomUUID();
        expectBookSuccess(flight, flightExternalId);
        expectBookError(hotel, HttpStatus.BAD_REQUEST);
        expectCancelSuccess(flight, flightExternalId);

        mockMvc.perform(post("/api/v1/orders/{id}/start", orderId))
                .andExpect(status().isInternalServerError());

        assertEquals(BookingStatus.CANCELLED, flight.getStatus());
        assertEquals(BookingStatus.FAILED, hotel.getStatus());
        assertEquals(BookingStatus.PENDING, order.getBookingSteps().get(2).getStatus());
        assertEquals(BookingStatus.PENDING, order.getBookingSteps().get(3).getStatus());
        assertEquals(OrderStatus.CANCELLED, order.getStatus());
        assertEquals(
                List.of("FLIGHT book", "HOTEL book", "FLIGHT cancel"),
                externalCalls);
        verifyExternalServices();
    }

    @Test
    void shouldCompensateInReverseOrderAfterThirdStepFailure() throws Exception {
        CompositeOrder order = createOrder(
                BookingType.FLIGHT,
                BookingType.HOTEL,
                BookingType.INSURANCE);
        BookingStep flight = order.getBookingSteps().get(0);
        BookingStep hotel = order.getBookingSteps().get(1);
        BookingStep insurance = order.getBookingSteps().get(2);
        UUID flightExternalId = UUID.randomUUID();
        UUID hotelExternalId = UUID.randomUUID();
        expectBookSuccess(flight, flightExternalId);
        expectBookSuccess(hotel, hotelExternalId);
        expectBookError(insurance, HttpStatus.BAD_REQUEST);
        expectCancelSuccess(hotel, hotelExternalId);
        expectCancelSuccess(flight, flightExternalId);

        mockMvc.perform(post("/api/v1/orders/{id}/start", orderId))
                .andExpect(status().isInternalServerError());

        assertEquals(
                List.of(
                        "FLIGHT book",
                        "HOTEL book",
                        "INSURANCE book",
                        "HOTEL cancel",
                        "FLIGHT cancel"),
                externalCalls);
        assertEquals(OrderStatus.CANCELLED, order.getStatus());
        verifyExternalServices();
    }

    @Test
    void shouldHandleConflictWithoutRetry() throws Exception {
        CompositeOrder order = createOrder(
                BookingType.FLIGHT,
                BookingType.HOTEL,
                BookingType.INSURANCE);
        BookingStep flight = order.getBookingSteps().get(0);
        BookingStep hotel = order.getBookingSteps().get(1);
        UUID flightExternalId = UUID.randomUUID();
        expectBookSuccess(flight, flightExternalId);
        expectBookError(hotel, HttpStatus.CONFLICT);
        expectCancelSuccess(flight, flightExternalId);

        mockMvc.perform(post("/api/v1/orders/{id}/start", orderId))
                .andExpect(status().isInternalServerError());

        assertEquals(1, externalCalls.stream()
                .filter("HOTEL book"::equals)
                .count());
        assertEquals(BookingStatus.FAILED, hotel.getStatus());
        assertEquals(BookingStatus.CANCELLED, flight.getStatus());
        assertEquals(BookingStatus.PENDING, order.getBookingSteps().get(2).getStatus());
        assertEquals(OrderStatus.CANCELLED, order.getStatus());
        verifyExternalServices();
    }

    @Test
    void shouldCompensateAfterExternalServerError() throws Exception {
        CompositeOrder order = createOrder(
                BookingType.FLIGHT,
                BookingType.HOTEL,
                BookingType.INSURANCE,
                BookingType.TRANSFER);
        BookingStep flight = order.getBookingSteps().get(0);
        BookingStep hotel = order.getBookingSteps().get(1);
        BookingStep insurance = order.getBookingSteps().get(2);
        UUID flightExternalId = UUID.randomUUID();
        UUID hotelExternalId = UUID.randomUUID();
        expectBookSuccess(flight, flightExternalId);
        expectBookSuccess(hotel, hotelExternalId);
        expectBookError(insurance, HttpStatus.INTERNAL_SERVER_ERROR);
        expectCancelSuccess(hotel, hotelExternalId);
        expectCancelSuccess(flight, flightExternalId);

        mockMvc.perform(post("/api/v1/orders/{id}/start", orderId))
                .andExpect(status().isInternalServerError());

        assertEquals(BookingStatus.FAILED, insurance.getStatus());
        assertEquals(BookingStatus.PENDING, order.getBookingSteps().get(3).getStatus());
        assertEquals(OrderStatus.CANCELLED, order.getStatus());
        assertEquals(
                List.of(
                        "FLIGHT book",
                        "HOTEL book",
                        "INSURANCE book",
                        "HOTEL cancel",
                        "FLIGHT cancel"),
                externalCalls);
        verifyExternalServices();
    }

    @Test
    void shouldCancelCompletedOrderExternallyInReverseOrder() throws Exception {
        CompositeOrder order = createCompletedOrder(
                BookingType.FLIGHT,
                BookingType.HOTEL);
        BookingStep flight = order.getBookingSteps().get(0);
        BookingStep hotel = order.getBookingSteps().get(1);
        expectCancelSuccess(hotel, UUID.fromString(hotel.getExternalId()));
        expectCancelSuccess(flight, UUID.fromString(flight.getExternalId()));

        mockMvc.perform(post("/api/v1/orders/{id}/cancel", orderId))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .jsonPath("$.status").value("CANCELLED"));

        assertEquals(
                List.of("HOTEL cancel", "FLIGHT cancel"),
                externalCalls);
        assertEquals(BookingStatus.CANCELLED, hotel.getStatus());
        assertEquals(BookingStatus.CANCELLED, flight.getStatus());
        assertEquals(OrderStatus.CANCELLED, order.getStatus());
        verifyExternalServices();
    }

    @Test
    void shouldCancelOnlyConfirmedStepsFromProcessingOrder() throws Exception {
        CompositeOrder order = createOrder(BookingType.FLIGHT, BookingType.HOTEL);
        order.startProcessing();
        BookingStep flight = order.startNextBookingStep().orElseThrow();
        UUID flightExternalId = UUID.randomUUID();
        flight.confirm(flightExternalId.toString());
        BookingStep hotel = order.getBookingSteps().get(1);
        expectCancelSuccess(flight, flightExternalId);

        mockMvc.perform(post("/api/v1/orders/{id}/cancel", orderId))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .jsonPath("$.status").value("CANCELLED"));

        assertEquals(List.of("FLIGHT cancel"), externalCalls);
        assertEquals(BookingStatus.CANCELLED, flight.getStatus());
        assertEquals(BookingStatus.PENDING, hotel.getStatus());
        assertEquals(OrderStatus.CANCELLED, order.getStatus());
        verifyExternalServices();
    }

    @Test
    void shouldStopManualCancellationOnServerError() throws Exception {
        CompositeOrder order = createCompletedOrder(
                BookingType.FLIGHT,
                BookingType.HOTEL);
        BookingStep flight = order.getBookingSteps().get(0);
        BookingStep hotel = order.getBookingSteps().get(1);
        expectCancelError(hotel, HttpStatus.INTERNAL_SERVER_ERROR);

        mockMvc.perform(post("/api/v1/orders/{id}/cancel", orderId))
                .andExpect(status().isInternalServerError());

        assertEquals(List.of("HOTEL cancel"), externalCalls);
        assertEquals(BookingStatus.CANCELLING, hotel.getStatus());
        assertEquals(BookingStatus.CONFIRMED, flight.getStatus());
        assertEquals(OrderStatus.CANCELLING, order.getStatus());
        verifyExternalServices();
    }

    @Test
    void shouldMapNotFoundDuringManualCancellation() {
        CompositeOrder order = createCompletedOrder(BookingType.HOTEL);
        BookingStep hotel = order.getBookingSteps().getFirst();
        expectCancelError(hotel, HttpStatus.NOT_FOUND);

        ExternalBookingException exception = assertThrows(
                ExternalBookingException.class,
                () -> orderService.cancelOrder(orderId));

        assertEquals(ExternalBookingErrorType.NOT_FOUND, exception.getErrorType());
        assertEquals(BookingStatus.CANCELLING, hotel.getStatus());
        assertEquals(OrderStatus.CANCELLING, order.getStatus());
        verifyExternalServices();
    }

    private CompositeOrder createOrder(BookingType... types) {
        CompositeOrder order = CompositeOrder.create();
        ReflectionTestUtils.setField(order, "id", orderId);
        for (BookingType type : types) {
            order.addBookingStep(type, UUID.randomUUID());
            ReflectionTestUtils.setField(
                    order.getBookingSteps().getLast(),
                    "id",
                    UUID.randomUUID());
        }
        storedOrder = order;
        return order;
    }

    private CompositeOrder createCompletedOrder(BookingType... types) {
        CompositeOrder order = createOrder(types);
        order.startProcessing();
        for (int i = 0; i < types.length; i++) {
            BookingStep step = order.startNextBookingStep().orElseThrow();
            step.confirm(UUID.randomUUID().toString());
        }
        order.complete();
        return order;
    }

    private void expectBookSuccess(BookingStep step, UUID externalId) {
        ExternalServiceMock service = services.get(step.getType());
        String path = bookingPath(step.getType());
        service.server().expect(ExpectedCount.once(), requestTo(service.baseUrl() + path))
                .andExpect(method(HttpMethod.POST))
                .andExpect(request -> externalCalls.add(step.getType() + " book"))
                .andExpect(jsonPath("$.resourceId")
                        .value(step.getResourceId().toString()))
                .andExpect(jsonPath("$.bookingReference")
                        .value(step.getId().toString()))
                .andRespond(withSuccess(
                        """
                                {
                                  "id": "%s",
                                  "bookingReference": "%s",
                                  "status": "CONFIRMED",
                                  "createdAt": "%s"
                                }
                                """.formatted(
                                externalId,
                                step.getId(),
                                Instant.parse("2026-10-17T10:00:00Z")),
                        MediaType.APPLICATION_JSON));
    }

    private void expectBookError(BookingStep step, HttpStatus status) {
        expectError(step, status, bookingPath(step.getType()), "book");
    }

    private void expectCancelSuccess(BookingStep step, UUID externalId) {
        ExternalServiceMock service = services.get(step.getType());
        String path = cancellationPath(step.getType());
        service.server().expect(ExpectedCount.once(), requestTo(service.baseUrl() + path))
                .andExpect(method(HttpMethod.POST))
                .andExpect(request -> externalCalls.add(step.getType() + " cancel"))
                .andExpect(jsonPath("$.bookingId").value(externalId.toString()))
                .andRespond(withSuccess(
                        """
                                {
                                  "id": "%s",
                                  "status": "CANCELLED",
                                  "updatedAt": "%s"
                                }
                                """.formatted(
                                externalId,
                                Instant.parse("2026-10-17T11:00:00Z")),
                        MediaType.APPLICATION_JSON));
    }

    private void expectCancelError(BookingStep step, HttpStatus status) {
        expectError(step, status, cancellationPath(step.getType()), "cancel");
    }

    private void expectError(
            BookingStep step,
            HttpStatus status,
            String path,
            String operation) {
        ExternalServiceMock service = services.get(step.getType());
        service.server().expect(ExpectedCount.once(), requestTo(service.baseUrl() + path))
                .andExpect(method(HttpMethod.POST))
                .andExpect(request -> externalCalls.add(
                        step.getType() + " " + operation))
                .andRespond(withStatus(status)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("""
                                {
                                  "success": false,
                                  "statusCode": %d,
                                  "message": "External %s failed",
                                  "error": "%s",
                                  "timestamp": "2026-10-17T10:00:00Z",
                                  "path": "%s"
                                }
                                """.formatted(
                                status.value(),
                                operation,
                                status.getReasonPhrase(),
                                path)));
    }

    private String bookingPath(BookingType type) {
        return "/api/v1/" + type.name().toLowerCase() + "/book";
    }

    private String cancellationPath(BookingType type) {
        return "/api/v1/" + type.name().toLowerCase() + "/cancel";
    }

    private void verifyExternalServices() {
        services.values().forEach(service -> service.server().verify());
    }

    private CompositeOrderRepository createRepository() {
        return (CompositeOrderRepository) Proxy.newProxyInstance(
                CompositeOrderRepository.class.getClassLoader(),
                new Class<?>[] { CompositeOrderRepository.class },
                (proxy, method, arguments) -> {
                    if (method.getName().equals("findById")) {
                        UUID id = (UUID) arguments[0];
                        return orderId.equals(id)
                                ? Optional.ofNullable(storedOrder)
                                : Optional.empty();
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
    }

    private record ExternalServiceMock(
            String baseUrl,
            MockRestServiceServer server) {
    }

    private static class NoOpTransactionManager implements PlatformTransactionManager {

        @Override
        public TransactionStatus getTransaction(TransactionDefinition definition) {
            return new SimpleTransactionStatus();
        }

        @Override
        public void commit(TransactionStatus status) {
        }

        @Override
        public void rollback(TransactionStatus status) {
        }
    }
}
