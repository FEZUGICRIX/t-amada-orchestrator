package com.tamada.travel.orchestrator;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tamada.travel.orchestrator.client.BookingClient;
import com.tamada.travel.orchestrator.client.BookingClientRegistry;
import com.tamada.travel.orchestrator.client.RestBookingClient;
import com.tamada.travel.orchestrator.controller.CompositeOrderController;
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
import java.math.BigDecimal;
import java.net.URI;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class PartialCompensationIntegrationTest {

    private final UUID orderId = UUID.randomUUID();
    private final Map<BookingType, ExternalServiceMock> services = new EnumMap<>(BookingType.class);
    private final List<String> externalCalls = new ArrayList<>();

    private CompositeOrder storedOrder;
    private CompositeOrderService orderService;
    private BookingOrchestrationService orchestrationService;
    private SagaCompensator compensator;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        externalCalls.clear();
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
        compensator = new SagaCompensator(registry, validator, repository, txTemplate, errorResolver, sseService, objectMapper);
        SagaExecutionLock lock = new SagaExecutionLock();

        orchestrationService = new BookingOrchestrationService(
                repository,
                txTemplate,
                stepExecutor,
                compensator,
                lock,
                errorResolver,
                sseService,
                objectMapper
        );
        orchestrationService.setPartialCompensationEnabled(true);

        OrderMapper orderMapper = new OrderMapper(objectMapper);
        orderService = new CompositeOrderService(
                repository,
                orchestrationService,
                null,
                new OrderValidator(),
                orderMapper,
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
    void shouldPreserveFlightTicketsWhenHotelFailsAndAllowHotelReplacementAndCompleteOrder() throws Exception {
        // 1. Создаем заказ: FLIGHT, HOTEL, TRANSFER
        CompositeOrder order = createOrder(
                BookingType.FLIGHT,
                BookingType.HOTEL,
                BookingType.TRANSFER
        );

        BookingStep flight = order.getBookingSteps().get(0);
        BookingStep hotel = order.getBookingSteps().get(1);
        BookingStep transfer = order.getBookingSteps().get(2);

        UUID flightExternalId = UUID.randomUUID();

        // 2. Мокаем: FLIGHT подтверждается успешно, а HOTEL падает с 409 Conflict
        expectBookSuccess(flight, flightExternalId);
        expectBookError(hotel, HttpStatus.CONFLICT);

        // 3. Запускаем оркестрацию (POST /orders/{id}/start)
        mockMvc.perform(post("/api/v1/orders/{id}/start", orderId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FAILED"));

        // 4. Проверяем состояние заказа:
        // Авиабилеты СОХРАНЕНЫ в CONFIRMED (полная компенсация НЕ запускалась!)
        assertEquals(OrderStatus.FAILED, order.getStatus());
        assertEquals(BookingStatus.CONFIRMED, flight.getStatus());
        assertEquals(flightExternalId.toString(), flight.getExternalId());

        // Отель перешел в статус FAILED
        assertEquals(BookingStatus.FAILED, hotel.getStatus());

        // Трансфер остался в PENDING
        assertEquals(BookingStatus.PENDING, transfer.getStatus());

        // Внешние вызовы: только FLIGHT book и HOTEL book (FLIGHT cancel НЕ вызывался!)
        assertEquals(List.of("FLIGHT book", "HOTEL book"), externalCalls);
        verifyExternalServices();
        resetExternalServices();

        // 5. Пользователь выбирает альтернативный отель через API замены:
        // POST /api/v1/orders/{id}/hotel/replace
        UUID alternativeHotelId = UUID.randomUUID();
        mockMvc.perform(
                post("/api/v1/orders/{id}/hotel/replace", orderId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "resourceId": "%s",
                                  "price": 9500.00
                                }
                                """.formatted(alternativeHotelId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.bookingSteps[1].resourceId").value(alternativeHotelId.toString()))
                .andExpect(jsonPath("$.bookingSteps[1].status").value("PENDING"));

        assertEquals(BookingStatus.PENDING, hotel.getStatus());
        assertEquals(alternativeHotelId, hotel.getResourceId());
        assertEquals(new BigDecimal("9500.00"), hotel.getPrice());

        // 6. Мокаем успешное бронирование альтернативного отеля и трансфера
        UUID newHotelExternalId = UUID.randomUUID();
        UUID transferExternalId = UUID.randomUUID();
        expectBookSuccess(hotel, newHotelExternalId);
        expectBookSuccess(transfer, transferExternalId);

        // 7. Повторно запускаем оркестрацию (POST /orders/{id}/start из статуса FAILED!)
        mockMvc.perform(post("/api/v1/orders/{id}/start", orderId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"));

        // 8. Заказ успешно завершен (COMPLETED), все услуги забронированы!
        assertEquals(OrderStatus.COMPLETED, order.getStatus());
        assertEquals(BookingStatus.CONFIRMED, flight.getStatus());
        assertEquals(BookingStatus.CONFIRMED, hotel.getStatus());
        assertEquals(newHotelExternalId.toString(), hotel.getExternalId());
        assertEquals(BookingStatus.CONFIRMED, transfer.getStatus());
        assertEquals(transferExternalId.toString(), transfer.getExternalId());

        // Проверяем полную цепочку вызовов: FLIGHT не вызывался повторно!
        assertEquals(
                List.of("FLIGHT book", "HOTEL book", "HOTEL book", "TRANSFER book"),
                externalCalls
        );
        verifyExternalServices();
    }

    @Test
    void shouldPartiallyCompensateConfirmedHotelWhenReplacingIt() throws Exception {
        // 1. Создаем заказ с FLIGHT и HOTEL
        CompositeOrder order = createOrder(
                BookingType.FLIGHT,
                BookingType.HOTEL
        );

        BookingStep flight = order.getBookingSteps().get(0);
        BookingStep hotel = order.getBookingSteps().get(1);

        UUID flightExternalId = UUID.randomUUID();
        UUID oldHotelExternalId = UUID.randomUUID();

        // 2. Оба шага подтверждаются
        expectBookSuccess(flight, flightExternalId);
        expectBookSuccess(hotel, oldHotelExternalId);

        mockMvc.perform(post("/api/v1/orders/{id}/start", orderId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"));

        assertEquals(OrderStatus.COMPLETED, order.getStatus());
        assertEquals(BookingStatus.CONFIRMED, flight.getStatus());
        assertEquals(BookingStatus.CONFIRMED, hotel.getStatus());
        verifyExternalServices();
        resetExternalServices();

        // 3. Пользователь решает заменить подтвержденный отель на альтернативный:
        // Должна сработать ЧАСТИЧНАЯ компенсация: /cancel вызывается ТОЛЬКО для старого отеля,
        // а авиабилеты остаются нетронутыми (CONFIRMED)!
        expectCancelSuccess(hotel, oldHotelExternalId);

        UUID alternativeHotelId = UUID.randomUUID();
        mockMvc.perform(
                put("/api/v1/orders/{id}/hotel", orderId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "resourceId": "%s",
                                  "price": 14000.00
                                }
                                """.formatted(alternativeHotelId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bookingSteps[1].resourceId").value(alternativeHotelId.toString()))
                .andExpect(jsonPath("$.bookingSteps[1].status").value("PENDING"))
                .andExpect(jsonPath("$.bookingSteps[0].status").value("CONFIRMED"));

        // Проверяем: старый отель был отменен во внешнем сервисе
        assertTrue(externalCalls.contains("HOTEL cancel"));
        assertFalse(externalCalls.contains("FLIGHT cancel"));
        assertEquals(BookingStatus.CONFIRMED, flight.getStatus());
        assertEquals(BookingStatus.PENDING, hotel.getStatus());
        verifyExternalServices();
        resetExternalServices();

        // 4. До-оркестрация альтернативного отеля
        UUID newHotelExternalId = UUID.randomUUID();
        expectBookSuccess(hotel, newHotelExternalId);

        mockMvc.perform(post("/api/v1/orders/{id}/start", orderId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"));

        assertEquals(OrderStatus.COMPLETED, order.getStatus());
        assertEquals(BookingStatus.CONFIRMED, flight.getStatus());
        assertEquals(BookingStatus.CONFIRMED, hotel.getStatus());
        assertEquals(newHotelExternalId.toString(), hotel.getExternalId());
        verifyExternalServices();
    }

    @Test
    void shouldCompensatePreservedTicketsWhenUserExplicitlyCancelsOrder() throws Exception {
        // 1. Создаем заказ: FLIGHT, HOTEL
        CompositeOrder order = createOrder(
                BookingType.FLIGHT,
                BookingType.HOTEL
        );

        BookingStep flight = order.getBookingSteps().get(0);
        BookingStep hotel = order.getBookingSteps().get(1);
        UUID flightExternalId = UUID.randomUUID();

        expectBookSuccess(flight, flightExternalId);
        expectBookError(hotel, HttpStatus.BAD_REQUEST);

        mockMvc.perform(post("/api/v1/orders/{id}/start", orderId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FAILED"));

        assertEquals(OrderStatus.FAILED, order.getStatus());
        assertEquals(BookingStatus.CONFIRMED, flight.getStatus());
        verifyExternalServices();
        resetExternalServices();

        // 2. Пользователь решает отменить весь заказ (POST /orders/{id}/cancel)
        // Теперь подтвержденный авиабилет должен быть компенсирован!
        expectCancelSuccess(flight, flightExternalId);

        mockMvc.perform(post("/api/v1/orders/{id}/cancel", orderId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));

        assertEquals(OrderStatus.CANCELLED, order.getStatus());
        assertEquals(BookingStatus.CANCELLED, flight.getStatus());
        assertEquals(BookingStatus.FAILED, hotel.getStatus());
        assertTrue(externalCalls.contains("FLIGHT cancel"));
        verifyExternalServices();
    }

    private CompositeOrder createOrder(BookingType... types) {
        CompositeOrder order = CompositeOrder.create();
        ReflectionTestUtils.setField(order, "id", orderId);

        for (BookingType type : types) {
            order.addBookingStep(type, UUID.randomUUID(), new BigDecimal("10000.00"));
            BookingStep step = order.getBookingSteps().getLast();
            ReflectionTestUtils.setField(step, "id", UUID.randomUUID());
        }

        storedOrder = order;
        return order;
    }

    private void expectBookSuccess(BookingStep step, UUID externalId) {
        ExternalServiceMock mock = services.get(step.getType());
        mock.server()
                .expect(ExpectedCount.once(), requestTo(mock.baseUrl() + "/api/v1/" + step.getType().name().toLowerCase() + "/book"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("""
                        {
                          "id": "%s",
                          "bookingReference": "%s",
                          "status": "CONFIRMED",
                          "confirmedAt": "2026-10-18T10:00:00Z"
                        }
                        """.formatted(externalId, step.getId()), MediaType.APPLICATION_JSON));

        externalCalls.add(step.getType() + " book");
    }

    private void expectBookError(BookingStep step, HttpStatus status) {
        ExternalServiceMock mock = services.get(step.getType());
        mock.server()
                .expect(ExpectedCount.once(), requestTo(mock.baseUrl() + "/api/v1/" + step.getType().name().toLowerCase() + "/book"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withStatus(status)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("""
                                {
                                  "success": false,
                                  "statusCode": %d,
                                  "message": "Resource unavailable",
                                  "error": "Conflict",
                                  "timestamp": "2026-10-18T10:00:00Z",
                                  "path": "/api/v1/%s/book"
                                }
                                """.formatted(status.value(), step.getType().name().toLowerCase())));

        externalCalls.add(step.getType() + " book");
    }

    private void expectCancelSuccess(BookingStep step, UUID externalId) {
        ExternalServiceMock mock = services.get(step.getType());
        mock.server()
                .expect(ExpectedCount.once(), requestTo(mock.baseUrl() + "/api/v1/" + step.getType().name().toLowerCase() + "/cancel"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("""
                        {
                          "id": "%s",
                          "status": "CANCELLED",
                          "updatedAt": "2026-10-18T10:00:00Z"
                        }
                        """.formatted(externalId), MediaType.APPLICATION_JSON));

        externalCalls.add(step.getType() + " cancel");
    }

    private void verifyExternalServices() {
        for (ExternalServiceMock mock : services.values()) {
            mock.server().verify();
        }
    }

    private void resetExternalServices() {
        for (ExternalServiceMock mock : services.values()) {
            mock.server().reset();
        }
    }

    private CompositeOrderRepository createRepository() {
        return (CompositeOrderRepository) Proxy.newProxyInstance(
                CompositeOrderRepository.class.getClassLoader(),
                new Class<?>[]{CompositeOrderRepository.class},
                (proxy, method, arguments) -> {
                    if (method.getName().equals("findById")) {
                        return Optional.ofNullable(storedOrder);
                    }
                    if (method.getName().equals("save")) {
                        storedOrder = (CompositeOrder) arguments[0];
                        return storedOrder;
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
