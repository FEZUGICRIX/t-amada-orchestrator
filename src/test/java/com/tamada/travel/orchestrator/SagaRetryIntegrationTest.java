package com.tamada.travel.orchestrator;

import com.tamada.travel.orchestrator.client.BookingClient;
import com.tamada.travel.orchestrator.client.BookingClientRegistry;
import com.tamada.travel.orchestrator.client.RestBookingClient;
import com.tamada.travel.orchestrator.client.RetryingBookingClient;
import com.tamada.travel.orchestrator.client.Sleeper;
import com.tamada.travel.orchestrator.config.BookingServicesProperties;
import com.tamada.travel.orchestrator.controller.CompositeOrderController;
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
import java.net.SocketTimeoutException;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class SagaRetryIntegrationTest {

    private final UUID orderId = UUID.randomUUID();
    private final Map<BookingType, ExternalServiceMock> services = new EnumMap<>(BookingType.class);
    private final List<String> externalCalls = new ArrayList<>();
    private final List<Duration> sleptDelays = new ArrayList<>();

    private CompositeOrder storedOrder;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        BookingServicesProperties.RetryProperties retryProperties = new BookingServicesProperties.RetryProperties(
                3,
                Duration.ofMillis(50),
                2.0,
                Duration.ofMillis(200)
        );
        Sleeper testSleeper = sleptDelays::add;

        List<BookingClient> clients = new ArrayList<>();
        for (BookingType type : BookingType.values()) {
            String serviceName = type.name().toLowerCase();
            String baseUrl = "http://" + serviceName + ".test";
            RestClient.Builder builder = RestClient.builder();
            MockRestServiceServer server = MockRestServiceServer
                    .bindTo(builder)
                    .build();

            BookingClient rawClient = new RestBookingClient(
                    type,
                    builder,
                    URI.create(baseUrl),
                    "/api/v1/" + serviceName + "/book",
                    "/api/v1/" + serviceName + "/cancel"
            );
            BookingClient retryingClient = new RetryingBookingClient(rawClient, retryProperties, testSleeper);
            clients.add(retryingClient);
            services.put(type, new ExternalServiceMock(baseUrl, server));
        }

        CompositeOrderRepository repository = createRepository();
        BookingOrchestrationService orchestrationService = new BookingOrchestrationService(
                repository,
                new BookingClientRegistry(clients),
                new TransactionTemplate(new NoOpTransactionManager())
        );
        CompositeOrderService orderService = new CompositeOrderService(
                repository,
                orchestrationService
        );
        CompositeOrderController controller = new CompositeOrderController(orderService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void shouldRetryBookingWhenSimulatorTimesOutAndSucceedOnSecondAttempt() throws Exception {
        CompositeOrder order = createOrder(BookingType.FLIGHT, BookingType.HOTEL);
        BookingStep flightStep = order.getBookingSteps().get(0);
        BookingStep hotelStep = order.getBookingSteps().get(1);

        UUID flightExternalId = UUID.randomUUID();
        UUID hotelExternalId = UUID.randomUUID();

        expectBookSuccess(flightStep, flightExternalId);

        // Имитатор отеля на 1-ю попытку бросает таймаут, на 2-ю попытку возвращает 200 CONFIRMED
        expectBookTimeout(hotelStep);
        expectBookSuccess(hotelStep, hotelExternalId);

        mockMvc.perform(post("/api/v1/orders/{id}/start", orderId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"));

        assertEquals(OrderStatus.COMPLETED, order.getStatus());
        assertEquals(BookingStatus.CONFIRMED, flightStep.getStatus());
        assertEquals(BookingStatus.CONFIRMED, hotelStep.getStatus());
        assertEquals(hotelExternalId.toString(), hotelStep.getExternalId());

        assertEquals(
                List.of("FLIGHT book", "HOTEL book", "HOTEL book"),
                externalCalls
        );
        assertEquals(1, sleptDelays.size());
        verifyExternalServices();
    }

    @Test
    void shouldRetryBookingWhenSimulatorReturns500AndSucceedOnThirdAttempt() throws Exception {
        CompositeOrder order = createOrder(BookingType.FLIGHT);
        BookingStep flightStep = order.getBookingSteps().get(0);
        UUID flightExternalId = UUID.randomUUID();

        // 1-я попытка 500, 2-я попытка 503, 3-я попытка 200 OK
        expectBookHttpError(flightStep, HttpStatus.INTERNAL_SERVER_ERROR);
        expectBookHttpError(flightStep, HttpStatus.SERVICE_UNAVAILABLE);
        expectBookSuccess(flightStep, flightExternalId);

        mockMvc.perform(post("/api/v1/orders/{id}/start", orderId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"));

        assertEquals(OrderStatus.COMPLETED, order.getStatus());
        assertEquals(BookingStatus.CONFIRMED, flightStep.getStatus());
        assertEquals(flightExternalId.toString(), flightStep.getExternalId());

        assertEquals(
                List.of("FLIGHT book", "FLIGHT book", "FLIGHT book"),
                externalCalls
        );
        assertEquals(2, sleptDelays.size());
        verifyExternalServices();
    }

    @Test
    void shouldExhaustRetriesOnPermanentSimulatorFailureAndCompensate() throws Exception {
        CompositeOrder order = createOrder(BookingType.FLIGHT, BookingType.HOTEL);
        BookingStep flightStep = order.getBookingSteps().get(0);
        BookingStep hotelStep = order.getBookingSteps().get(1);

        UUID flightExternalId = UUID.randomUUID();
        expectBookSuccess(flightStep, flightExternalId);

        // Имитатор отеля не отвечает 3 раза подряд (все 3 попытки таймаут)
        expectBookTimeout(hotelStep);
        expectBookTimeout(hotelStep);
        expectBookTimeout(hotelStep);

        // После исчерпания попыток должна запуститься компенсация рейса
        expectCancelSuccess(flightStep, flightExternalId);

        mockMvc.perform(post("/api/v1/orders/{id}/start", orderId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));

        assertEquals(OrderStatus.CANCELLED, order.getStatus());
        assertEquals(BookingStatus.CANCELLED, flightStep.getStatus());
        assertEquals(BookingStatus.FAILED, hotelStep.getStatus());

        assertEquals(
                List.of(
                        "FLIGHT book",
                        "HOTEL book",
                        "HOTEL book",
                        "HOTEL book",
                        "FLIGHT cancel"
                ),
                externalCalls
        );
        assertEquals(2, sleptDelays.size());
        verifyExternalServices();
    }

    @Test
    void shouldNotRetryNonRetryableErrorAndCompensateImmediately() throws Exception {
        CompositeOrder order = createOrder(BookingType.FLIGHT, BookingType.HOTEL);
        BookingStep flightStep = order.getBookingSteps().get(0);
        BookingStep hotelStep = order.getBookingSteps().get(1);

        UUID flightExternalId = UUID.randomUUID();
        expectBookSuccess(flightStep, flightExternalId);
        expectBookHttpError(hotelStep, HttpStatus.CONFLICT);
        expectCancelSuccess(flightStep, flightExternalId);

        mockMvc.perform(post("/api/v1/orders/{id}/start", orderId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));

        assertEquals(OrderStatus.CANCELLED, order.getStatus());
        assertEquals(BookingStatus.CANCELLED, flightStep.getStatus());
        assertEquals(BookingStatus.FAILED, hotelStep.getStatus());

        assertEquals(
                List.of("FLIGHT book", "HOTEL book", "FLIGHT cancel"),
                externalCalls
        );
        assertEquals(0, sleptDelays.size());
        verifyExternalServices();
    }

    private void expectBookSuccess(BookingStep step, UUID externalId) {
        ExternalServiceMock service = services.get(step.getType());
        service.server()
                .expect(requestTo(service.baseUrl() + "/api/v1/" + step.getType().name().toLowerCase() + "/book"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(
                        """
                                {
                                  "id": "%s",
                                  "bookingReference": "%s",
                                  "status": "CONFIRMED",
                                  "createdAt": "%s"
                                }
                                """.formatted(externalId, step.getId(), Instant.now()),
                        MediaType.APPLICATION_JSON
                ));
        externalCalls.add(step.getType().name() + " book");
    }

    private void expectBookTimeout(BookingStep step) {
        ExternalServiceMock service = services.get(step.getType());
        service.server()
                .expect(requestTo(service.baseUrl() + "/api/v1/" + step.getType().name().toLowerCase() + "/book"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withException(new SocketTimeoutException("Read timed out")));
        externalCalls.add(step.getType().name() + " book");
    }

    private void expectBookHttpError(BookingStep step, HttpStatus status) {
        ExternalServiceMock service = services.get(step.getType());
        service.server()
                .expect(requestTo(service.baseUrl() + "/api/v1/" + step.getType().name().toLowerCase() + "/book"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withStatus(status)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("""
                                {
                                  "success": false,
                                  "statusCode": %d,
                                  "message": "External simulation failure",
                                  "error": "%s",
                                  "timestamp": "%s",
                                  "path": "/api/v1/%s/book"
                                }
                                """.formatted(
                                status.value(),
                                status.getReasonPhrase(),
                                Instant.now(),
                                step.getType().name().toLowerCase()
                        )));
        externalCalls.add(step.getType().name() + " book");
    }

    private void expectCancelSuccess(BookingStep step, UUID externalId) {
        ExternalServiceMock service = services.get(step.getType());
        service.server()
                .expect(requestTo(service.baseUrl() + "/api/v1/" + step.getType().name().toLowerCase() + "/cancel"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(
                        """
                                {
                                  "id": "%s",
                                  "status": "CANCELLED",
                                  "updatedAt": "%s"
                                }
                                """.formatted(externalId, Instant.now()),
                        MediaType.APPLICATION_JSON
                ));
        externalCalls.add(step.getType().name() + " cancel");
    }

    private CompositeOrder createOrder(BookingType... types) {
        CompositeOrder order = CompositeOrder.create();
        ReflectionTestUtils.setField(order, "id", orderId);
        for (BookingType type : types) {
            order.addBookingStep(type, UUID.randomUUID());
            ReflectionTestUtils.setField(
                    order.getBookingSteps().getLast(),
                    "id",
                    UUID.randomUUID()
            );
        }
        storedOrder = order;
        return order;
    }

    private CompositeOrderRepository createRepository() {
        return (CompositeOrderRepository) Proxy.newProxyInstance(
                CompositeOrderRepository.class.getClassLoader(),
                new Class<?>[]{CompositeOrderRepository.class},
                (proxy, method, arguments) -> {
                    if (method.getName().equals("findById")) {
                        UUID id = (UUID) arguments[0];
                        return orderId.equals(id)
                                ? Optional.ofNullable(storedOrder)
                                : Optional.empty();
                    }
                    if (method.getName().equals("save")) {
                        storedOrder = (CompositeOrder) arguments[0];
                        return storedOrder;
                    }
                    throw new UnsupportedOperationException("Not implemented in test: " + method.getName());
                }
        );
    }

    private void verifyExternalServices() {
        for (ExternalServiceMock mock : services.values()) {
            mock.server().verify();
        }
    }

    private record ExternalServiceMock(String baseUrl, MockRestServiceServer server) {
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
