package com.tamada.travel.orchestrator;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tamada.travel.orchestrator.client.BookingClient;
import com.tamada.travel.orchestrator.client.BookingClientRegistry;
import com.tamada.travel.orchestrator.client.RestBookingClient;
import com.tamada.travel.orchestrator.controller.CompositeOrderController;
import com.tamada.travel.orchestrator.dto.CancelOrderRequest;
import com.tamada.travel.orchestrator.dto.CreateBookingStepRequest;
import com.tamada.travel.orchestrator.dto.CreateOrderRequest;
import com.tamada.travel.orchestrator.exception.GlobalExceptionHandler;
import com.tamada.travel.orchestrator.model.BookingStatus;
import com.tamada.travel.orchestrator.model.BookingStep;
import com.tamada.travel.orchestrator.model.BookingType;
import com.tamada.travel.orchestrator.model.CompositeOrder;
import com.tamada.travel.orchestrator.model.OrderStatus;
import com.tamada.travel.orchestrator.repository.CompositeOrderRepository;
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
import java.math.BigDecimal;
import java.net.URI;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Интеграционный тест унифицированных путей API и общих каналов обработки.
 */
class UniversalEndpointsIntegrationTest {

    private final UUID orderId = UUID.randomUUID();
    private final Map<BookingType, ExternalServiceMock> services = new EnumMap<>(BookingType.class);

    private UUID nextStepId;
    private CompositeOrder storedOrder;
    private CompositeOrderService orderService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        nextStepId = null;
        services.clear();
        UserErrorMessageResolver errorResolver = new UserErrorMessageResolver();
        OrderSseService sseService = new OrderSseService();
        ObjectMapper objectMapper = new ObjectMapper();

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

        BookingOrchestrationService orchestrationService = new BookingOrchestrationService(
                repository,
                txTemplate,
                stepExecutor,
                compensator,
                new SagaExecutionLock(),
                errorResolver,
                sseService,
                objectMapper
        );

        orderService = new CompositeOrderService(
                repository,
                orchestrationService,
                null,
                new OrderValidator(),
                new OrderMapper(),
                null,
                sseService,
                null,
                compensator
        );

        CompositeOrderController controller = new CompositeOrderController(orderService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void shouldModifyStepViaUniversalPutEndpoint() throws Exception {
        UUID flightResource = UUID.randomUUID();
        UUID hotelResource = UUID.randomUUID();
        UUID newHotelResource = UUID.randomUUID();

        storedOrder = CompositeOrder.create();
        ReflectionTestUtils.setField(storedOrder, "id", orderId);
        BookingStep flightStep = storedOrder.addBookingStep(BookingType.FLIGHT, flightResource, new BigDecimal("10000.00"));
        ReflectionTestUtils.setField(flightStep, "id", UUID.randomUUID());

        BookingStep hotelStep = storedOrder.addBookingStep(BookingType.HOTEL, hotelResource, new BigDecimal("5000.00"));
        ReflectionTestUtils.setField(hotelStep, "id", UUID.randomUUID());

        mockMvc.perform(put("/api/v1/orders/{id}/steps", orderId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "type": "HOTEL",
                                  "resourceId": "%s",
                                  "price": 7500.00
                                }
                                """.formatted(newHotelResource)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bookingSteps[1].resourceId").value(newHotelResource.toString()))
                .andExpect(jsonPath("$.bookingSteps[1].price").value(7500.00))
                .andExpect(jsonPath("$.basePrice").value(17500.00));

        assertEquals(newHotelResource, hotelStep.getResourceId());
        assertEquals(new BigDecimal("7500.00"), hotelStep.getPrice());
    }

    @Test
    void shouldCreateAndStartOrderInOneRequest() throws Exception {
        UUID flightResource = UUID.randomUUID();
        UUID externalBookingId = UUID.randomUUID();
        nextStepId = UUID.randomUUID();

        expectBookSuccess(BookingType.FLIGHT, flightResource, nextStepId, externalBookingId);

        mockMvc.perform(post("/api/v1/orders?start=true")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "bookings": [
                                    {
                                      "type": "FLIGHT",
                                      "resourceId": "%s",
                                      "price": 12000.00
                                    }
                                  ]
                                }
                                """.formatted(flightResource)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.bookingSteps[0].status").value("CONFIRMED"))
                .andExpect(jsonPath("$.bookingSteps[0].externalId").value(externalBookingId.toString()));
    }

    @Test
    void shouldStartAsyncWhenPreferRespondAsyncHeaderProvided() throws Exception {
        storedOrder = CompositeOrder.create();
        ReflectionTestUtils.setField(storedOrder, "id", orderId);
        BookingStep flightStep = storedOrder.addBookingStep(BookingType.FLIGHT, UUID.randomUUID(), new BigDecimal("10000.00"));
        ReflectionTestUtils.setField(flightStep, "id", UUID.randomUUID());

        mockMvc.perform(post("/api/v1/orders/{id}/start", orderId)
                        .header("Prefer", "respond-async"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("PROCESSING"));
    }

    @Test
    void shouldCancelSingleStepViaUnifiedCancelEndpointWithStepIdInBody() throws Exception {
        storedOrder = CompositeOrder.create();
        ReflectionTestUtils.setField(storedOrder, "id", orderId);
        BookingStep flightStep = storedOrder.addBookingStep(BookingType.FLIGHT, UUID.randomUUID(), new BigDecimal("10000.00"));
        ReflectionTestUtils.setField(flightStep, "id", UUID.randomUUID());

        UUID transferStepId = UUID.randomUUID();
        BookingStep transferStep = storedOrder.addBookingStep(BookingType.TRANSFER, UUID.randomUUID(), new BigDecimal("2000.00"));
        ReflectionTestUtils.setField(transferStep, "id", transferStepId);

        mockMvc.perform(post("/api/v1/orders/{id}/cancel", orderId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "stepId": "%s"
                                }
                                """.formatted(transferStepId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bookingSteps[1].cancellationReason").value("Отменено пользователем"));
    }

    @Test
    void shouldCancelOrderViaHttpDeleteMethod() throws Exception {
        storedOrder = CompositeOrder.create();
        ReflectionTestUtils.setField(storedOrder, "id", orderId);
        storedOrder.startProcessing();

        mockMvc.perform(delete("/api/v1/orders/{id}", orderId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
    }

    private void expectBookSuccess(BookingType type, UUID resourceId, UUID stepId, UUID externalBookingId) {
        ExternalServiceMock mock = services.get(type);
        mock.server.expect(ExpectedCount.once(), requestTo(mock.baseUrl + "/api/v1/" + type.name().toLowerCase() + "/book"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(
                        """
                        {
                          "id": "%s",
                          "resourceId": "%s",
                          "bookingReference": "%s",
                          "status": "CONFIRMED"
                        }
                        """.formatted(externalBookingId, resourceId, stepId),
                        MediaType.APPLICATION_JSON));
    }

    private CompositeOrderRepository createRepository() {
        return (CompositeOrderRepository) Proxy.newProxyInstance(
                CompositeOrderRepository.class.getClassLoader(),
                new Class<?>[]{CompositeOrderRepository.class},
                (proxy, method, args) -> {
                    String methodName = method.getName();
                    if ("findById".equals(methodName)) {
                        return Optional.ofNullable(storedOrder);
                    }
                    if ("save".equals(methodName)) {
                        storedOrder = (CompositeOrder) args[0];
                        if (storedOrder.getId() == null) {
                            ReflectionTestUtils.setField(storedOrder, "id", orderId);
                        }
                        for (BookingStep s : storedOrder.getBookingSteps()) {
                            if (s.getId() == null) {
                                UUID id = nextStepId != null ? nextStepId : UUID.randomUUID();
                                ReflectionTestUtils.setField(s, "id", id);
                            }
                        }
                        return storedOrder;
                    }
                    return null;
                });
    }

    private static class ExternalServiceMock {
        private final String baseUrl;
        private final MockRestServiceServer server;

        private ExternalServiceMock(String baseUrl, MockRestServiceServer server) {
            this.baseUrl = baseUrl;
            this.server = server;
        }
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
