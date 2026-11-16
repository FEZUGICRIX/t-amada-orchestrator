package com.tamada.travel.orchestrator;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tamada.travel.orchestrator.client.BookingClient;
import com.tamada.travel.orchestrator.client.BookingClientRegistry;
import com.tamada.travel.orchestrator.client.RestBookingClient;
import com.tamada.travel.orchestrator.controller.CompositeOrderController;
import com.tamada.travel.orchestrator.dto.OrderResponse;
import com.tamada.travel.orchestrator.exception.GlobalExceptionHandler;
import com.tamada.travel.orchestrator.model.BookingStatus;
import com.tamada.travel.orchestrator.model.BookingStep;
import com.tamada.travel.orchestrator.model.BookingType;
import com.tamada.travel.orchestrator.model.CompositeOrder;
import com.tamada.travel.orchestrator.model.OrderStatus;
import com.tamada.travel.orchestrator.repository.CompositeOrderRepository;
import com.tamada.travel.orchestrator.repository.OrderOperationHistoryRepository;
import com.tamada.travel.orchestrator.service.BookingOrchestrationService;
import com.tamada.travel.orchestrator.service.CompositeOrderService;
import com.tamada.travel.orchestrator.service.error.UserErrorMessageResolver;
import com.tamada.travel.orchestrator.service.mapper.OrderMapper;
import com.tamada.travel.orchestrator.service.orchestration.SagaCompensator;
import com.tamada.travel.orchestrator.service.orchestration.SagaExecutionLock;
import com.tamada.travel.orchestrator.service.orchestration.StepExecutor;
import com.tamada.travel.orchestrator.service.orchestration.StepResponseValidator;
import com.tamada.travel.orchestrator.service.sse.OrderSseService;
import com.tamada.travel.orchestrator.service.validation.OrderValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;

import java.lang.reflect.Proxy;
import java.net.URI;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class OrderErrorAndSseIntegrationTest {

    private final UUID orderId = UUID.randomUUID();
    private final Map<BookingType, ExternalServiceMock> services = new EnumMap<>(BookingType.class);

    private CompositeOrder storedOrder;
    private CompositeOrderService orderService;
    private OrderSseService sseService;
    private MockMvc mockMvc;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        UserErrorMessageResolver errorResolver = new UserErrorMessageResolver();
        sseService = new OrderSseService();

        List<BookingClient> clients = new ArrayList<>();
        for (BookingType type : BookingType.values()) {
            String serviceName = type.name().toLowerCase();
            String baseUrl = "http://" + serviceName + ".test";
            RestClient.Builder builder = RestClient.builder();
            MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
            clients.add(new RestBookingClient(
                    type,
                    builder,
                    URI.create(baseUrl),
                    "/api/v1/" + serviceName + "/book",
                    "/api/v1/" + serviceName + "/cancel",
                    errorResolver
            ));
            services.put(type, new ExternalServiceMock(baseUrl, server));
        }

        BookingClientRegistry registry = new BookingClientRegistry(clients);
        StepResponseValidator validator = new StepResponseValidator();
        CompositeOrderRepository repository = createRepository();
        TransactionTemplate txTemplate = new TransactionTemplate(new NoOpTransactionManager());

        StepExecutor stepExecutor = new StepExecutor(registry, validator, repository, txTemplate);
        SagaCompensator compensator = new SagaCompensator(registry, validator, repository, txTemplate, errorResolver, sseService, objectMapper);
        SagaExecutionLock lock = new SagaExecutionLock();

        BookingOrchestrationService orchestrationService = new BookingOrchestrationService(
                repository,
                txTemplate,
                stepExecutor,
                compensator,
                lock,
                errorResolver,
                sseService,
                objectMapper
        );

        OrderMapper orderMapper = new OrderMapper(objectMapper);
        orderService = new CompositeOrderService(
                repository,
                orchestrationService,
                null,
                new OrderValidator(),
                orderMapper,
                null,
                sseService
        );

        CompositeOrderController controller = new CompositeOrderController(orderService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void shouldSaveDetailedProviderErrorsAndCancellationReasonsWhenStepFails() throws Exception {
        CompositeOrder order = createOrder(
                BookingType.FLIGHT,
                BookingType.HOTEL,
                BookingType.INSURANCE,
                BookingType.TRANSFER
        );

        BookingStep flight = order.getBookingSteps().get(0);
        BookingStep hotel = order.getBookingSteps().get(1);
        UUID flightExternalId = UUID.randomUUID();

        // 1. FLIGHT succeeds
        expectBookSuccess(flight, flightExternalId);

        // 2. HOTEL fails with 409 Conflict and structured JSON error from supplier
        String hotelErrorJson = """
                {
                  "success": false,
                  "statusCode": 409,
                  "message": "Resource is unavailable",
                  "error": "Conflict",
                  "timestamp": "2026-10-18T10:00:00Z",
                  "path": "/api/v1/hotel/book"
                }
                """;
        expectBookError(hotel, HttpStatus.CONFLICT, hotelErrorJson);

        // 3. FLIGHT is compensated
        expectCancelSuccess(flight, flightExternalId);

        // Run orchestration
        MvcResult mvcResult = mockMvc.perform(post("/api/v1/orders/{id}/start", orderId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.failureReason").isNotEmpty())
                .andExpect(jsonPath("$.userMessage").isNotEmpty())
                .andReturn();

        // Verify order status and messages
        assertEquals(OrderStatus.CANCELLED, order.getStatus());
        assertNotNull(order.getFailureReason());
        assertTrue(order.getFailureReason().contains("HOTEL"));
        assertTrue(order.getFailureReason().contains("Resource is unavailable"));
        assertNotNull(order.getUserMessage());
        assertTrue(order.getUserMessage().contains("номер в отеле недоступен"));

        // Verify Step 0 (FLIGHT) was compensated
        assertEquals(BookingStatus.CANCELLED, flight.getStatus());
        assertNotNull(flight.getCancellationReason());
        assertTrue(flight.getCancellationReason().contains("компенсация"));
        assertTrue(flight.getCancellationReason().contains("отеля"));
        assertNotNull(flight.getUserMessage());
        assertTrue(flight.getUserMessage().contains("авиабилета"));

        // Verify Step 1 (HOTEL) failed with detailed error
        assertEquals(BookingStatus.FAILED, hotel.getStatus());
        assertEquals("RESOURCE_UNAVAILABLE", hotel.getErrorCode());
        assertEquals("Resource is unavailable", hotel.getErrorMessage());
        assertNotNull(hotel.getUserMessage());
        assertTrue(hotel.getUserMessage().contains("номер в отеле недоступен"));
        assertNotNull(hotel.getErrorDetails());
        assertTrue(hotel.getErrorDetails().contains("\"statusCode\":409"));
        assertTrue(hotel.getErrorDetails().contains("\"error\":\"Conflict\""));
        assertTrue(hotel.getErrorDetails().contains("\"path\":\"/api/v1/hotel/book\""));

        // Verify Step 2 & 3 (INSURANCE, TRANSFER) were skipped with cancellation reason
        BookingStep insurance = order.getBookingSteps().get(2);
        BookingStep transfer = order.getBookingSteps().get(3);
        assertEquals(BookingStatus.PENDING, insurance.getStatus());
        assertNotNull(insurance.getCancellationReason());
        assertTrue(insurance.getCancellationReason().contains("не выполнялось"));
        assertTrue(insurance.getCancellationReason().contains("отеля"));
        assertNotNull(insurance.getUserMessage());

        assertEquals(BookingStatus.PENDING, transfer.getStatus());
        assertNotNull(transfer.getCancellationReason());
        assertTrue(transfer.getCancellationReason().contains("не выполнялось"));
        assertTrue(transfer.getCancellationReason().contains("отеля"));
        assertNotNull(transfer.getUserMessage());

        // Verify operation history was recorded
        assertFalse(order.getOperationHistory().isEmpty());
        assertTrue(order.getOperationHistory().stream().anyMatch(h -> "BOOK".equals(h.getOperationType()) && "FAILED".equals(h.getStatus())));
        assertTrue(order.getOperationHistory().stream().anyMatch(h -> "COMPENSATION".equals(h.getOperationType()) && "CANCELLED".equals(h.getStatus())));
        assertTrue(order.getOperationHistory().stream().anyMatch(h -> "SKIP".equals(h.getOperationType()) && "SKIPPED".equals(h.getStatus())));

        // Test GET /api/v1/orders/{id}/history endpoint
        mockMvc.perform(get("/api/v1/orders/{id}/history", orderId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$[?(@.status == 'FAILED')].errorCode").value("RESOURCE_UNAVAILABLE"))
                .andExpect(jsonPath("$[?(@.status == 'FAILED')].httpStatus").value(409))
                .andExpect(jsonPath("$[?(@.status == 'FAILED')].message").value("Resource is unavailable"))
                .andExpect(jsonPath("$[?(@.operationType == 'COMPENSATION')].cancellationReason").isNotEmpty());

        // Test GET /api/v1/orders/{id}/events SSE endpoint
        mockMvc.perform(get("/api/v1/orders/{id}/events", orderId))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM_VALUE));
    }

    @Test
    void shouldRecordReasonsWhenOrderIsCancelledManually() throws Exception {
        CompositeOrder order = createOrder(
                BookingType.FLIGHT,
                BookingType.HOTEL
        );

        order.startProcessing();
        BookingStep flight = order.startNextBookingStep().orElseThrow();
        UUID flightExternalId = UUID.randomUUID();
        flight.confirm(flightExternalId.toString());

        BookingStep hotel = order.startNextBookingStep().orElseThrow();
        UUID hotelExternalId = UUID.randomUUID();
        hotel.confirm(hotelExternalId.toString());
        order.complete();

        assertEquals(OrderStatus.COMPLETED, order.getStatus());

        // Expect manual cancel of both steps
        expectCancelSuccess(hotel, hotelExternalId);
        expectCancelSuccess(flight, flightExternalId);

        mockMvc.perform(post("/api/v1/orders/{id}/cancel", orderId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));

        assertEquals(OrderStatus.CANCELLED, order.getStatus());
        assertEquals("Отменено пользователем", order.getFailureReason());

        for (BookingStep step : order.getBookingSteps()) {
            assertEquals(BookingStatus.CANCELLED, step.getStatus());
            assertEquals("Отменено по запросу пользователя", step.getCancellationReason());
            assertNotNull(step.getUserMessage());
        }

        assertTrue(order.getOperationHistory().stream().anyMatch(h -> "MANUAL_CANCEL".equals(h.getOperationType())));
    }

    private CompositeOrder createOrder(BookingType... types) {
        CompositeOrder order = CompositeOrder.create();
        org.springframework.test.util.ReflectionTestUtils.setField(order, "id", orderId);
        for (BookingType type : types) {
            order.addBookingStep(type, UUID.randomUUID());
            org.springframework.test.util.ReflectionTestUtils.setField(
                    order.getBookingSteps().getLast(),
                    "id",
                    UUID.randomUUID()
            );
        }
        storedOrder = order;
        return order;
    }

    private void expectBookSuccess(BookingStep step, UUID externalBookingId) {
        ExternalServiceMock service = services.get(step.getType());
        String body = """
                {
                  "id": "%s",
                  "bookingReference": "%s",
                  "status": "CONFIRMED",
                  "timestamp": "2026-10-18T10:00:00Z"
                }
                """.formatted(externalBookingId, step.getId());

        service.server()
                .expect(requestTo(service.baseUrl() + "/api/v1/" + step.getType().name().toLowerCase() + "/book"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
    }

    private void expectBookError(BookingStep step, HttpStatus status, String responseBody) {
        ExternalServiceMock service = services.get(step.getType());
        service.server()
                .expect(requestTo(service.baseUrl() + "/api/v1/" + step.getType().name().toLowerCase() + "/book"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withStatus(status).body(responseBody).contentType(MediaType.APPLICATION_JSON));
    }

    private void expectCancelSuccess(BookingStep step, UUID externalBookingId) {
        ExternalServiceMock service = services.get(step.getType());
        String body = """
                {
                  "id": "%s",
                  "status": "CANCELLED",
                  "timestamp": "2026-10-18T10:00:00Z"
                }
                """.formatted(externalBookingId);

        service.server()
                .expect(requestTo(service.baseUrl() + "/api/v1/" + step.getType().name().toLowerCase() + "/cancel"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
    }

    private CompositeOrderRepository createRepository() {
        return (CompositeOrderRepository) Proxy.newProxyInstance(
                CompositeOrderRepository.class.getClassLoader(),
                new Class<?>[]{CompositeOrderRepository.class},
                (proxy, method, args) -> {
                    String name = method.getName();
                    if ("findById".equals(name)) {
                        UUID id = (UUID) args[0];
                        if (orderId.equals(id) && storedOrder != null) {
                            return Optional.of(storedOrder);
                        }
                        return Optional.empty();
                    }
                    if ("save".equals(name)) {
                        storedOrder = (CompositeOrder) args[0];
                        return storedOrder;
                    }
                    throw new UnsupportedOperationException("Unsupported method: " + name);
                });
    }

    private record ExternalServiceMock(
            String baseUrl,
            MockRestServiceServer server
    ) {
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
