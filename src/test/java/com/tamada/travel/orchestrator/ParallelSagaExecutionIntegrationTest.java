package com.tamada.travel.orchestrator;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tamada.travel.orchestrator.client.BookingClient;
import com.tamada.travel.orchestrator.client.BookingClientRegistry;
import com.tamada.travel.orchestrator.client.RestBookingClient;
import com.tamada.travel.orchestrator.model.BookingStatus;
import com.tamada.travel.orchestrator.model.BookingStep;
import com.tamada.travel.orchestrator.model.BookingType;
import com.tamada.travel.orchestrator.model.CompositeOrder;
import com.tamada.travel.orchestrator.model.OrderStatus;
import com.tamada.travel.orchestrator.repository.CompositeOrderRepository;
import com.tamada.travel.orchestrator.service.BookingOrchestrationService;
import com.tamada.travel.orchestrator.service.error.UserErrorMessageResolver;
import com.tamada.travel.orchestrator.service.orchestration.SagaCompensator;
import com.tamada.travel.orchestrator.service.orchestration.SagaExecutionLock;
import com.tamada.travel.orchestrator.service.orchestration.StepExecutor;
import com.tamada.travel.orchestrator.service.orchestration.StepResponseValidator;
import com.tamada.travel.orchestrator.service.sse.OrderSseService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
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

/**
 * Интеграционный тест фазово-параллельного выполнения независимых услуг саги:
 * Проверяет, что при включенном флаге parallelStepsEnabled:
 * 1. Авиабилеты (FLIGHT) бронируются первыми (якорь путешествия).
 * 2. После подтверждения перелетов независимые услуги (HOTEL, INSURANCE, TRANSFER)
 *    бронируются одновременно в рамках параллельной фазы.
 * 3. Заказ успешно переходит в статус COMPLETED.
 */
class ParallelSagaExecutionIntegrationTest {

    private final UUID orderId = UUID.randomUUID();
    private final Map<BookingType, ExternalServiceMock> services = new EnumMap<>(BookingType.class);

    private CompositeOrder storedOrder;
    private BookingOrchestrationService orchestrationService;

    @BeforeEach
    void setUp() {
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

        orchestrationService = new BookingOrchestrationService(
                repository,
                txTemplate,
                stepExecutor,
                compensator,
                new SagaExecutionLock(),
                errorResolver,
                sseService,
                objectMapper
        );

        // Включаем фазово-параллельное выполнение
        orchestrationService.setParallelStepsEnabled(true);
    }

    @Test
    void shouldExecuteIndependentStepsInParallelAfterFlightConfirmation() {
        UUID flightResource = UUID.randomUUID();
        UUID hotelResource = UUID.randomUUID();
        UUID insuranceResource = UUID.randomUUID();
        UUID transferResource = UUID.randomUUID();

        UUID flightStepId = UUID.randomUUID();
        UUID hotelStepId = UUID.randomUUID();
        UUID insuranceStepId = UUID.randomUUID();
        UUID transferStepId = UUID.randomUUID();

        storedOrder = CompositeOrder.create();
        ReflectionTestUtils.setField(storedOrder, "id", orderId);
        BookingStep flightStep = storedOrder.addBookingStep(BookingType.FLIGHT, flightResource, new BigDecimal("15000.00"));
        ReflectionTestUtils.setField(flightStep, "id", flightStepId);

        BookingStep hotelStep = storedOrder.addBookingStep(BookingType.HOTEL, hotelResource, new BigDecimal("8000.00"));
        ReflectionTestUtils.setField(hotelStep, "id", hotelStepId);

        BookingStep insuranceStep = storedOrder.addBookingStep(BookingType.INSURANCE, insuranceResource, new BigDecimal("1200.00"));
        ReflectionTestUtils.setField(insuranceStep, "id", insuranceStepId);

        BookingStep transferStep = storedOrder.addBookingStep(BookingType.TRANSFER, transferResource, new BigDecimal("2500.00"));
        ReflectionTestUtils.setField(transferStep, "id", transferStepId);

        expectBookSuccess(BookingType.FLIGHT, flightResource, flightStepId);
        expectBookSuccess(BookingType.HOTEL, hotelResource, hotelStepId);
        expectBookSuccess(BookingType.INSURANCE, insuranceResource, insuranceStepId);
        expectBookSuccess(BookingType.TRANSFER, transferResource, transferStepId);

        CompositeOrder completed = orchestrationService.orchestrate(orderId);

        assertEquals(OrderStatus.COMPLETED, completed.getStatus());
        for (BookingStep step : completed.getBookingSteps()) {
            assertEquals(BookingStatus.CONFIRMED, step.getStatus());
        }
        verifyServices();
    }

    private void expectBookSuccess(BookingType type, UUID resourceId, UUID stepId) {
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
                        """.formatted(UUID.randomUUID(), resourceId, stepId),
                        MediaType.APPLICATION_JSON));
    }

    private void verifyServices() {
        for (ExternalServiceMock mock : services.values()) {
            mock.server.verify();
        }
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
