package com.tamada.travel.orchestrator;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.tamada.travel.orchestrator.client.BookingClient;
import com.tamada.travel.orchestrator.client.BookingClientRegistry;
import com.tamada.travel.orchestrator.client.RestBookingClient;
import com.tamada.travel.orchestrator.config.SagaRecoveryProperties;
import com.tamada.travel.orchestrator.model.BookingStatus;
import com.tamada.travel.orchestrator.model.BookingStep;
import com.tamada.travel.orchestrator.model.BookingType;
import com.tamada.travel.orchestrator.model.CompositeOrder;
import com.tamada.travel.orchestrator.model.OrderStatus;
import com.tamada.travel.orchestrator.model.OutboxMessage;
import com.tamada.travel.orchestrator.repository.CompositeOrderRepository;
import com.tamada.travel.orchestrator.repository.OutboxRepository;
import com.tamada.travel.orchestrator.service.BookingOrchestrationService;
import com.tamada.travel.orchestrator.service.CompositeOrderService;
import com.tamada.travel.orchestrator.service.OutboxService;
import com.tamada.travel.orchestrator.service.error.UserErrorMessageResolver;
import com.tamada.travel.orchestrator.service.mapper.OrderMapper;
import com.tamada.travel.orchestrator.service.orchestration.SagaCompensator;
import com.tamada.travel.orchestrator.service.orchestration.SagaExecutionLock;
import com.tamada.travel.orchestrator.service.orchestration.SagaRecoveryWorker;
import com.tamada.travel.orchestrator.service.orchestration.StepExecutor;
import com.tamada.travel.orchestrator.service.orchestration.StepResponseValidator;
import com.tamada.travel.orchestrator.service.validation.OrderValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.match.MockRestRequestMatchers;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;

import java.lang.reflect.Proxy;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Интеграционный тест авто-восстановления зависших заказов (Saga Recovery).
 * <p>
 * Проверяет:
 * 1. Поиск зависших заказов со статусом PROCESSING старше 2 минут при старте и по таймеру.
 * 2. Уже выкупленные билеты/отели не бронируются повторно.
 * 3. Симуляцию падения сервиса и авто-подхват саги.
 * 4. Успешное доведение до COMPLETED при успехе оставшихся шагов.
 * 5. Компенсацию ранее подтвержденных шагов и доведение до CANCELLED при сбое.
 * 6. Игнорирование не зависших заказов (моложе 2 минут или в других статусах).
 */
class SagaRecoveryIntegrationTest {

    private final Instant now = Instant.parse("2026-11-19T14:00:00Z");
    private final Clock clock = Clock.fixed(now, ZoneId.of("UTC"));
    private final Duration stuckThreshold = Duration.ofMinutes(2);

    private final Map<BookingType, ExternalServiceMock> services = new EnumMap<>(BookingType.class);
    private final List<String> externalCalls = new ArrayList<>();
    private final List<OutboxMessage> savedOutboxMessages = new ArrayList<>();

    private final Map<UUID, CompositeOrder> ordersDatabase = new HashMap<>();

    private SagaRecoveryWorker recoveryWorker;

    @BeforeEach
    void setUp() {
        ordersDatabase.clear();
        externalCalls.clear();
        savedOutboxMessages.clear();
        services.clear();

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
                    "/api/v1/" + serviceName + "/cancel"
            ));
            services.put(type, new ExternalServiceMock(baseUrl, server));
        }

        BookingClientRegistry registry = new BookingClientRegistry(clients);
        TransactionTemplate transactionTemplate = new TransactionTemplate(new NoOpTransactionManager());
        CompositeOrderRepository repository = createRepositoryProxy();

        StepResponseValidator validator = new StepResponseValidator();
        UserErrorMessageResolver resolver = new UserErrorMessageResolver();
        ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

        StepExecutor stepExecutor = new StepExecutor(registry, validator, repository, transactionTemplate);
        SagaCompensator compensator = new SagaCompensator(
                registry, validator, repository, transactionTemplate, resolver, null, objectMapper
        );
        SagaExecutionLock executionLock = new SagaExecutionLock();

        BookingOrchestrationService orchestrationService = new BookingOrchestrationService(
                repository,
                transactionTemplate,
                stepExecutor,
                compensator,
                executionLock,
                resolver,
                null,
                objectMapper
        );

        OutboxRepository outboxRepository = createOutboxRepositoryProxy();
        OutboxService outboxService = new OutboxService(outboxRepository, objectMapper);

        CompositeOrderService orderService = new CompositeOrderService(
                repository,
                orchestrationService,
                outboxService,
                new OrderValidator(),
                new OrderMapper(),
                null,
                null
        );

        SagaRecoveryProperties properties = new SagaRecoveryProperties(
                true,
                stuckThreshold,
                Duration.ofSeconds(60),
                Duration.ofSeconds(60)
        );

        recoveryWorker = new SagaRecoveryWorker(repository, orderService, properties, clock);
    }

    @Test
    void shouldAutoRecoverStuckOrderAndCompleteSuccessfullyWithoutRebookingConfirmedSteps() {
        // Симуляция падения: заказ завис в PROCESSING 5 минут назад
        // Шаг 1: FLIGHT уже CONFIRMED (куплен до сбоя)
        // Шаг 2: HOTEL остался в PENDING
        CompositeOrder order = CompositeOrder.create();
        BookingStep flightStep = order.addBookingStep(BookingType.FLIGHT, UUID.randomUUID());
        BookingStep hotelStep = order.addBookingStep(BookingType.HOTEL, UUID.randomUUID());

        UUID flightBookingId = UUID.randomUUID();
        UUID hotelBookingId = UUID.randomUUID();

        // Переводим заказ в PROCESSING и подтверждаем первый шаг, как будто сервер упал после этого
        order.startProcessing();
        BookingStep startedFlight = order.startNextBookingStep().orElseThrow();
        startedFlight.confirm(flightBookingId.toString());

        // Устанавливаем updatedAt на 5 минут назад (заказ завис > 2 минут)
        Instant stuckTime = now.minus(Duration.ofMinutes(5));
        ReflectionTestUtils.setField(order, "updatedAt", stuckTime);

        saveOrder(order);

        // Ожидания для внешних сервисов:
        // 1. FLIGHT book НЕ ДОЛЖЕН вызываться повторно (уже куплен!)
        // 2. HOTEL book ДОЛЖЕН быть вызван
        expectBookSuccess(hotelStep, hotelBookingId);

        // Запуск восстановления зависших заказов (симуляция авто-подхвата)
        int recovered = recoveryWorker.recoverStuckOrders();

        assertEquals(1, recovered);
        assertEquals(OrderStatus.COMPLETED, order.getStatus());

        // Проверяем, что FLIGHT сохранил свой исходный внешний ID и остался CONFIRMED
        assertEquals(BookingStatus.CONFIRMED, flightStep.getStatus());
        assertEquals(flightBookingId.toString(), flightStep.getExternalId());

        // Проверяем, что HOTEL успешно подтвержден
        assertEquals(BookingStatus.CONFIRMED, hotelStep.getStatus());
        assertEquals(hotelBookingId.toString(), hotelStep.getExternalId());

        // Проверяем, что был вызван ТОЛЬКО HOTEL book, а FLIGHT book повторно не вызывался
        assertEquals(List.of("HOTEL book"), externalCalls);
        verifyExternalServices();

        // Проверяем Outbox событие COMPLETED
        assertTrue(savedOutboxMessages.stream()
                .anyMatch(msg -> OutboxService.EVENT_ORDER_COMPLETED.equals(msg.getEventType())));
    }

    @Test
    void shouldAutoRecoverInterruptedProcessingStepAndComplete() {
        // Симуляция падения посреди бронирования HOTEL:
        // FLIGHT - CONFIRMED
        // HOTEL - завис со статусом PROCESSING (externalId = null)
        CompositeOrder order = CompositeOrder.create();
        BookingStep flightStep = order.addBookingStep(BookingType.FLIGHT, UUID.randomUUID());
        BookingStep hotelStep = order.addBookingStep(BookingType.HOTEL, UUID.randomUUID());

        UUID flightBookingId = UUID.randomUUID();
        UUID hotelBookingId = UUID.randomUUID();

        order.startProcessing();
        BookingStep step1 = order.startNextBookingStep().orElseThrow();
        step1.confirm(flightBookingId.toString());

        // Второй шаг успел перейти в PROCESSING до падения сервера
        BookingStep step2 = order.startNextBookingStep().orElseThrow();
        assertEquals(BookingStatus.PROCESSING, step2.getStatus());

        ReflectionTestUtils.setField(order, "updatedAt", now.minus(Duration.ofMinutes(3)));
        saveOrder(order);

        expectBookSuccess(hotelStep, hotelBookingId);

        int recovered = recoveryWorker.recoverStuckOrders();

        assertEquals(1, recovered);
        assertEquals(OrderStatus.COMPLETED, order.getStatus());
        assertEquals(BookingStatus.CONFIRMED, flightStep.getStatus());
        assertEquals(flightBookingId.toString(), flightStep.getExternalId());
        assertEquals(BookingStatus.CONFIRMED, hotelStep.getStatus());
        assertEquals(hotelBookingId.toString(), hotelStep.getExternalId());

        assertEquals(List.of("HOTEL book"), externalCalls);
        verifyExternalServices();
    }

    @Test
    void shouldCompensateConfirmedStepsAndCancelOrderWhenRecoveredStepFails() {
        // Симуляция падения и последующей компенсации:
        // FLIGHT - CONFIRMED
        // HOTEL - PENDING
        // При авто-подхвате HOTEL завершается ошибкой -> FLIGHT должен быть компенсирован через /cancel
        CompositeOrder order = CompositeOrder.create();
        BookingStep flightStep = order.addBookingStep(BookingType.FLIGHT, UUID.randomUUID());
        BookingStep hotelStep = order.addBookingStep(BookingType.HOTEL, UUID.randomUUID());

        UUID flightBookingId = UUID.randomUUID();

        order.startProcessing();
        BookingStep startedFlight = order.startNextBookingStep().orElseThrow();
        startedFlight.confirm(flightBookingId.toString());

        ReflectionTestUtils.setField(order, "updatedAt", now.minus(Duration.ofMinutes(4)));
        saveOrder(order);

        // HOTEL вернет 500 ошибку, а затем для FLIGHT должен вызваться /cancel
        expectBookError(hotelStep, HttpStatus.INTERNAL_SERVER_ERROR);
        expectCancelSuccess(flightStep, flightBookingId);

        int recovered = recoveryWorker.recoverStuckOrders();

        assertEquals(1, recovered);
        assertEquals(OrderStatus.CANCELLED, order.getStatus());
        assertEquals(BookingStatus.CANCELLED, flightStep.getStatus());
        assertEquals(BookingStatus.FAILED, hotelStep.getStatus());

        assertEquals(List.of("HOTEL book", "FLIGHT cancel"), externalCalls);
        verifyExternalServices();

        // Проверяем Outbox событие CANCELLED
        assertTrue(savedOutboxMessages.stream()
                .anyMatch(msg -> OutboxService.EVENT_ORDER_CANCELLED.equals(msg.getEventType())));
    }

    @Test
    void shouldIgnoreOrdersModifiedRecentlyUnderTwoMinutes() {
        // Заказ находится в PROCESSING, но обновлен всего 30 секунд назад (не завис, нормальная работа)
        CompositeOrder activeOrder = CompositeOrder.create();
        activeOrder.addBookingStep(BookingType.FLIGHT, UUID.randomUUID());
        activeOrder.startProcessing();
        ReflectionTestUtils.setField(activeOrder, "updatedAt", now.minus(Duration.ofSeconds(30)));
        saveOrder(activeOrder);

        int recovered = recoveryWorker.recoverStuckOrders();

        assertEquals(0, recovered);
        assertEquals(OrderStatus.PROCESSING, activeOrder.getStatus());
        assertTrue(externalCalls.isEmpty());
    }

    @Test
    void shouldIgnoreOrdersInNonProcessingStatuses() {
        // Заказы в NEW, COMPLETED, CANCELLED старше 2 минут не должны подхватываться
        CompositeOrder newOrder = CompositeOrder.create();
        ReflectionTestUtils.setField(newOrder, "updatedAt", now.minus(Duration.ofMinutes(10)));
        saveOrder(newOrder);

        CompositeOrder completedOrder = CompositeOrder.create();
        ReflectionTestUtils.setField(completedOrder, "status", OrderStatus.COMPLETED);
        ReflectionTestUtils.setField(completedOrder, "updatedAt", now.minus(Duration.ofMinutes(10)));
        saveOrder(completedOrder);

        CompositeOrder cancelledOrder = CompositeOrder.create();
        ReflectionTestUtils.setField(cancelledOrder, "status", OrderStatus.CANCELLED);
        ReflectionTestUtils.setField(cancelledOrder, "updatedAt", now.minus(Duration.ofMinutes(10)));
        saveOrder(cancelledOrder);

        int recovered = recoveryWorker.recoverStuckOrders();

        assertEquals(0, recovered);
        assertTrue(externalCalls.isEmpty());
    }

    @Test
    void shouldTriggerRecoveryViaApplicationReadyEvent() {
        // Проверка чеклиста: поиск зависших заказов при старте приложения
        CompositeOrder orderOnStartup = createStuckOrderWithFlightConfirmed();
        UUID hotelId1 = UUID.randomUUID();
        expectBookSuccess(orderOnStartup.getBookingSteps().get(1), hotelId1);

        recoveryWorker.onApplicationReady();

        assertEquals(OrderStatus.COMPLETED, orderOnStartup.getStatus());
        verifyExternalServices();
    }

    @Test
    void shouldTriggerRecoveryViaScheduledTrigger() {
        // Проверка чеклиста: поиск зависших заказов по таймеру
        CompositeOrder orderOnTimer = createStuckOrderWithFlightConfirmed();
        UUID hotelId2 = UUID.randomUUID();
        expectBookSuccess(orderOnTimer.getBookingSteps().get(1), hotelId2);

        recoveryWorker.scheduledRecovery();

        assertEquals(OrderStatus.COMPLETED, orderOnTimer.getStatus());
        verifyExternalServices();
    }

    private CompositeOrder createStuckOrderWithFlightConfirmed() {
        CompositeOrder order = CompositeOrder.create();
        order.addBookingStep(BookingType.FLIGHT, UUID.randomUUID());
        order.addBookingStep(BookingType.HOTEL, UUID.randomUUID());

        order.startProcessing();
        BookingStep flight = order.startNextBookingStep().orElseThrow();
        flight.confirm(UUID.randomUUID().toString());

        ReflectionTestUtils.setField(order, "updatedAt", now.minus(Duration.ofMinutes(5)));
        saveOrder(order);
        return order;
    }

    private void saveOrder(CompositeOrder order) {
        if (order.getId() == null) {
            ReflectionTestUtils.setField(order, "id", UUID.randomUUID());
        }
        for (BookingStep step : order.getBookingSteps()) {
            if (step.getId() == null) {
                ReflectionTestUtils.setField(step, "id", UUID.randomUUID());
            }
        }
        ordersDatabase.put(order.getId(), order);
    }

    private void expectBookSuccess(BookingStep step, UUID externalId) {
        ExternalServiceMock service = services.get(step.getType());
        String path = "/api/v1/" + step.getType().name().toLowerCase() + "/book";

        service.server().expect(ExpectedCount.once(), requestTo(service.baseUrl() + path))
                .andExpect(method(HttpMethod.POST))
                .andExpect(request -> externalCalls.add(step.getType() + " book"))
                .andRespond(withSuccess(
                        """
                                {
                                  "id": "%s",
                                  "bookingReference": "%s",
                                  "status": "CONFIRMED",
                                  "confirmedAt": "%s"
                                }
                                """.formatted(externalId, step.getId(), Instant.parse("2026-10-17T10:00:00Z")),
                        MediaType.APPLICATION_JSON
                ));
    }

    private void expectBookError(BookingStep step, HttpStatus status) {
        ExternalServiceMock service = services.get(step.getType());
        String path = "/api/v1/" + step.getType().name().toLowerCase() + "/book";

        service.server().expect(ExpectedCount.once(), requestTo(service.baseUrl() + path))
                .andExpect(method(HttpMethod.POST))
                .andExpect(request -> externalCalls.add(step.getType() + " book"))
                .andRespond(withStatus(status)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("""
                                {
                                  "success": false,
                                  "statusCode": %d,
                                  "message": "External booking failed",
                                  "error": "%s",
                                  "timestamp": "2026-10-17T10:00:00Z",
                                  "path": "%s"
                                }
                                """.formatted(status.value(), status.getReasonPhrase(), path)));
    }

    private void expectCancelSuccess(BookingStep step, UUID externalId) {
        ExternalServiceMock service = services.get(step.getType());
        String path = "/api/v1/" + step.getType().name().toLowerCase() + "/cancel";

        service.server().expect(ExpectedCount.once(), requestTo(service.baseUrl() + path))
                .andExpect(method(HttpMethod.POST))
                .andExpect(request -> externalCalls.add(step.getType() + " cancel"))
                .andExpect(MockRestRequestMatchers.jsonPath("$.bookingId").value(externalId.toString()))
                .andRespond(withSuccess(
                        """
                                {
                                  "id": "%s",
                                  "status": "CANCELLED",
                                  "updatedAt": "%s"
                                }
                                """.formatted(externalId, Instant.parse("2026-10-17T11:00:00Z")),
                        MediaType.APPLICATION_JSON
                ));
    }

    private void verifyExternalServices() {
        services.values().forEach(service -> service.server().verify());
    }

    private CompositeOrderRepository createRepositoryProxy() {
        return (CompositeOrderRepository) Proxy.newProxyInstance(
                CompositeOrderRepository.class.getClassLoader(),
                new Class<?>[]{CompositeOrderRepository.class},
                (proxy, method, arguments) -> {
                    String methodName = method.getName();
                    if (methodName.equals("findById")) {
                        UUID id = (UUID) arguments[0];
                        return Optional.ofNullable(ordersDatabase.get(id));
                    }
                    if (methodName.equals("save")) {
                        CompositeOrder entity = (CompositeOrder) arguments[0];
                        saveOrder(entity);
                        return entity;
                    }
                    if (methodName.equals("findByStatusAndUpdatedAtBeforeOrderByUpdatedAtAsc")) {
                        OrderStatus status = (OrderStatus) arguments[0];
                        Instant cutoff = (Instant) arguments[1];
                        return ordersDatabase.values().stream()
                                .filter(o -> o.getStatus() == status && o.getUpdatedAt().isBefore(cutoff))
                                .sorted(Comparator.comparing(CompositeOrder::getUpdatedAt))
                                .toList();
                    }
                    throw new UnsupportedOperationException("Unsupported method on repository: " + methodName);
                }
        );
    }

    private OutboxRepository createOutboxRepositoryProxy() {
        return (OutboxRepository) Proxy.newProxyInstance(
                OutboxRepository.class.getClassLoader(),
                new Class<?>[]{OutboxRepository.class},
                (proxy, method, arguments) -> {
                    if (method.getName().equals("save")) {
                        OutboxMessage msg = (OutboxMessage) arguments[0];
                        savedOutboxMessages.add(msg);
                        return msg;
                    }
                    throw new UnsupportedOperationException(method.getName());
                }
        );
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
