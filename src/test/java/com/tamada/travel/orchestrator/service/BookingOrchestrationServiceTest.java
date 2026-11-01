package com.tamada.travel.orchestrator.service;

import com.tamada.travel.orchestrator.client.BookingClient;
import com.tamada.travel.orchestrator.client.BookingClientRegistry;
import com.tamada.travel.orchestrator.client.dto.BookingRequest;
import com.tamada.travel.orchestrator.client.dto.BookingResponse;
import com.tamada.travel.orchestrator.client.dto.CancelBookingRequest;
import com.tamada.travel.orchestrator.client.dto.CancelBookingResponse;
import com.tamada.travel.orchestrator.exception.ExternalBookingErrorType;
import com.tamada.travel.orchestrator.exception.ExternalBookingException;
import com.tamada.travel.orchestrator.exception.InvalidBookingDataException;
import com.tamada.travel.orchestrator.model.BookingStatus;
import com.tamada.travel.orchestrator.model.BookingStep;
import com.tamada.travel.orchestrator.model.BookingType;
import com.tamada.travel.orchestrator.model.CompositeOrder;
import com.tamada.travel.orchestrator.model.OrderStatus;
import com.tamada.travel.orchestrator.repository.CompositeOrderRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BookingOrchestrationServiceTest {

    private final UUID orderId = UUID.randomUUID();
    private final List<BookingType> invocationOrder = new ArrayList<>();
    private final List<BookingType> cancellationOrder = new ArrayList<>();
    private final Map<BookingType, MockBookingClient> clients =
            new EnumMap<>(BookingType.class);

    private CompositeOrder storedOrder;
    private BookingOrchestrationService service;

    @BeforeEach
    void setUp() {
        for (BookingType type : BookingType.values()) {
            clients.put(type, new MockBookingClient(
                    type,
                    invocationOrder,
                    cancellationOrder
            ));
        }

        BookingClientRegistry registry = new BookingClientRegistry(
                new ArrayList<>(clients.values())
        );
        TransactionTemplate transactionTemplate = new TransactionTemplate(
                new NoOpTransactionManager()
        );

        service = new BookingOrchestrationService(
                createRepository(),
                registry,
                transactionTemplate
        );
    }

    @Test
    void shouldBookSingleStepSuccessfully() {
        CompositeOrder order = createOrder(BookingType.FLIGHT);
        BookingStep step = order.getBookingSteps().getFirst();
        UUID externalBookingId = stubSuccessfulBooking(step);

        service.orchestrate(orderId);

        assertEquals(BookingStatus.CONFIRMED, step.getStatus());
        assertEquals(externalBookingId.toString(), step.getExternalId());
        assertEquals(OrderStatus.COMPLETED, order.getStatus());
    }

    @Test
    void shouldBookMultipleStepsSequentially() {
        CompositeOrder order = createOrder(BookingType.FLIGHT, BookingType.HOTEL);
        BookingStep flightStep = order.getBookingSteps().get(0);
        BookingStep hotelStep = order.getBookingSteps().get(1);
        stubSuccessfulBooking(flightStep);
        stubSuccessfulBooking(hotelStep);

        service.orchestrate(orderId);

        assertEquals(
                List.of(BookingType.FLIGHT, BookingType.HOTEL),
                invocationOrder
        );
        assertEquals(BookingStatus.CONFIRMED, flightStep.getStatus());
        assertEquals(BookingStatus.CONFIRMED, hotelStep.getStatus());
    }

    @Test
    void shouldUseCorrectClientForBookingType() {
        CompositeOrder order = createOrder(
                BookingType.FLIGHT,
                BookingType.HOTEL,
                BookingType.INSURANCE,
                BookingType.TRANSFER
        );
        order.getBookingSteps().forEach(this::stubSuccessfulBooking);

        service.orchestrate(orderId);

        for (BookingType type : BookingType.values()) {
            assertEquals(1, clients.get(type).requests().size());
        }
    }

    @Test
    void shouldSendResourceIdAndBookingReference() {
        CompositeOrder order = createOrder(BookingType.FLIGHT);
        BookingStep step = order.getBookingSteps().getFirst();
        stubSuccessfulBooking(step);

        service.orchestrate(orderId);

        BookingRequest request = clients.get(BookingType.FLIGHT)
                .requests()
                .getFirst();
        assertEquals(step.getResourceId(), request.resourceId());
        assertEquals(step.getId(), request.bookingReference());
    }

    @Test
    void shouldSaveExternalBookingId() {
        CompositeOrder order = createOrder(BookingType.FLIGHT);
        BookingStep step = order.getBookingSteps().getFirst();
        UUID externalBookingId = stubSuccessfulBooking(step);

        service.orchestrate(orderId);

        assertEquals(externalBookingId.toString(), step.getExternalId());
    }

    @Test
    void shouldCompleteOrderWhenAllStepsConfirmed() {
        CompositeOrder order = createOrder(BookingType.FLIGHT, BookingType.HOTEL);
        order.getBookingSteps().forEach(this::stubSuccessfulBooking);

        service.orchestrate(orderId);

        assertEquals(OrderStatus.COMPLETED, order.getStatus());
    }

    @Test
    void shouldFailCurrentStepWhenBookingClientThrows() {
        CompositeOrder order = createOrder(BookingType.FLIGHT);
        BookingStep step = order.getBookingSteps().getFirst();
        stubFailedBooking(step);

        service.orchestrate(orderId);

        assertEquals(BookingStatus.FAILED, step.getStatus());
    }

    @Test
    void shouldFailOrderWhenBookingClientThrows() {
        CompositeOrder order = createOrder(BookingType.FLIGHT);
        BookingStep step = order.getBookingSteps().getFirst();
        stubFailedBooking(step);

        service.orchestrate(orderId);

        assertEquals(OrderStatus.CANCELLED, order.getStatus());
    }

    @Test
    void shouldFailStepOnBadRequest() {
        CompositeOrder order = orchestrateExternalFailure(
                HttpStatus.BAD_REQUEST,
                ExternalBookingErrorType.VALIDATION_ERROR
        );

        assertEquals(
                BookingStatus.FAILED,
                order.getBookingSteps().getFirst().getStatus()
        );
    }

    @Test
    void shouldFailOrderOnBadRequest() {
        CompositeOrder order = orchestrateExternalFailure(
                HttpStatus.BAD_REQUEST,
                ExternalBookingErrorType.VALIDATION_ERROR
        );

        assertEquals(OrderStatus.CANCELLED, order.getStatus());
    }

    @Test
    void shouldFailStepOnNotFound() {
        CompositeOrder order = orchestrateExternalFailure(
                HttpStatus.NOT_FOUND,
                ExternalBookingErrorType.NOT_FOUND
        );

        assertEquals(
                BookingStatus.FAILED,
                order.getBookingSteps().getFirst().getStatus()
        );
    }

    @Test
    void shouldFailOrderOnNotFound() {
        CompositeOrder order = orchestrateExternalFailure(
                HttpStatus.NOT_FOUND,
                ExternalBookingErrorType.NOT_FOUND
        );

        assertEquals(OrderStatus.CANCELLED, order.getStatus());
    }

    @Test
    void shouldFailStepOnConflict() {
        CompositeOrder order = orchestrateExternalFailure(
                HttpStatus.CONFLICT,
                ExternalBookingErrorType.RESOURCE_UNAVAILABLE
        );

        assertEquals(
                BookingStatus.FAILED,
                order.getBookingSteps().getFirst().getStatus()
        );
    }

    @Test
    void shouldFailOrderOnConflict() {
        CompositeOrder order = orchestrateExternalFailure(
                HttpStatus.CONFLICT,
                ExternalBookingErrorType.RESOURCE_UNAVAILABLE
        );

        assertEquals(OrderStatus.CANCELLED, order.getStatus());
    }

    @Test
    void shouldNotStartNextStepAfterConflict() {
        CompositeOrder order = createOrder(BookingType.FLIGHT, BookingType.HOTEL);
        BookingStep firstStep = order.getBookingSteps().getFirst();
        stubExternalFailure(
                firstStep,
                HttpStatus.CONFLICT,
                ExternalBookingErrorType.RESOURCE_UNAVAILABLE
        );

        service.orchestrate(orderId);

        assertEquals(1, clients.get(BookingType.FLIGHT).requests().size());
        assertEquals(BookingStatus.FAILED, firstStep.getStatus());
        assertEquals(
                BookingStatus.PENDING,
                order.getBookingSteps().getLast().getStatus()
        );
        assertTrue(clients.get(BookingType.HOTEL).requests().isEmpty());
    }

    @Test
    void shouldFailStepOnExternalServerError() {
        CompositeOrder order = orchestrateExternalFailure(
                HttpStatus.INTERNAL_SERVER_ERROR,
                ExternalBookingErrorType.SYSTEM_FAILURE
        );

        assertEquals(
                BookingStatus.FAILED,
                order.getBookingSteps().getFirst().getStatus()
        );
    }

    @Test
    void shouldFailOrderOnExternalServerError() {
        CompositeOrder order = orchestrateExternalFailure(
                HttpStatus.INTERNAL_SERVER_ERROR,
                ExternalBookingErrorType.SYSTEM_FAILURE
        );

        assertEquals(OrderStatus.CANCELLED, order.getStatus());
    }

    @Test
    void shouldCompensateConfirmedStepsExternally() {
        PartialFailure result = orchestratePartialFailure();

        assertEquals(BookingStatus.CANCELLED, result.flightStep().getStatus());
        assertEquals(BookingStatus.CANCELLED, result.hotelStep().getStatus());
        assertEquals(1, clients.get(BookingType.FLIGHT)
                .cancellationRequests().size());
        assertEquals(1, clients.get(BookingType.HOTEL)
                .cancellationRequests().size());
    }

    @Test
    void shouldCompensateInReverseOrder() {
        orchestratePartialFailure();

        assertEquals(
                List.of(BookingType.HOTEL, BookingType.FLIGHT),
                cancellationOrder
        );
    }

    @Test
    void shouldUseCorrectClientForCancellation() {
        orchestratePartialFailure();

        assertEquals(1, clients.get(BookingType.FLIGHT)
                .cancellationRequests().size());
        assertEquals(1, clients.get(BookingType.HOTEL)
                .cancellationRequests().size());
        assertTrue(clients.get(BookingType.INSURANCE)
                .cancellationRequests().isEmpty());
        assertTrue(clients.get(BookingType.TRANSFER)
                .cancellationRequests().isEmpty());
    }

    @Test
    void shouldSendExternalIdToCancel() {
        PartialFailure result = orchestratePartialFailure();

        assertEquals(
                result.flightExternalId(),
                clients.get(BookingType.FLIGHT)
                        .cancellationRequests().getFirst().bookingId()
        );
        assertEquals(
                result.hotelExternalId(),
                clients.get(BookingType.HOTEL)
                        .cancellationRequests().getFirst().bookingId()
        );
    }

    @Test
    void shouldCancelOrderAfterSuccessfulCompensation() {
        PartialFailure result = orchestratePartialFailure();

        assertEquals(OrderStatus.CANCELLED, result.order().getStatus());
    }

    @Test
    void shouldKeepFailedStepFailed() {
        PartialFailure result = orchestratePartialFailure();

        assertEquals(BookingStatus.FAILED, result.insuranceStep().getStatus());
    }

    @Test
    void shouldKeepPendingStepsPending() {
        PartialFailure result = orchestratePartialFailure();

        assertEquals(BookingStatus.PENDING, result.transferStep().getStatus());
    }

    @Test
    void shouldStartCompensationAfterBookingFailure() {
        orchestratePartialFailure();

        assertEquals(2, cancellationOrder.size());
    }

    @Test
    void shouldNotCallCancelForPendingStep() {
        orchestratePartialFailure();

        assertTrue(clients.get(BookingType.TRANSFER)
                .cancellationRequests().isEmpty());
    }

    @Test
    void shouldNotCallCancelForFailedStep() {
        orchestratePartialFailure();

        assertTrue(clients.get(BookingType.INSURANCE)
                .cancellationRequests().isEmpty());
    }

    @Test
    void shouldFailCompensationWhenExternalIdMissing() {
        CompositeOrder order = createOrder(BookingType.FLIGHT, BookingType.HOTEL);
        BookingStep flightStep = order.getBookingSteps().getFirst();
        BookingStep hotelStep = order.getBookingSteps().getLast();
        stubSuccessfulBooking(flightStep);
        clients.get(BookingType.HOTEL).respondWith(request -> {
            ReflectionTestUtils.setField(flightStep, "externalId", null);
            throw new RuntimeException("Hotel booking failed");
        });

        InvalidBookingDataException exception = assertThrows(
                InvalidBookingDataException.class,
                () -> service.orchestrate(orderId)
        );

        assertTrue(exception.getMessage().contains("External booking id is missing"));
        assertEquals(BookingStatus.CONFIRMED, flightStep.getStatus());
        assertEquals(BookingStatus.FAILED, hotelStep.getStatus());
        assertEquals(OrderStatus.FAILED, order.getStatus());
        assertTrue(cancellationOrder.isEmpty());
    }

    @Test
    void shouldNotMarkStepCancelledWhenCancelRequestFails() {
        CompensationFailure result = orchestrateCancellationFailure();

        assertEquals(BookingStatus.CANCELLING, result.hotelStep().getStatus());
        assertEquals(BookingStatus.CONFIRMED, result.flightStep().getStatus());
    }

    @Test
    void shouldNotMarkOrderCancelledWhenCompensationFails() {
        CompensationFailure result = orchestrateCancellationFailure();

        assertEquals(OrderStatus.CANCELLING, result.order().getStatus());
    }

    @Test
    void shouldNotStartLaterStepsAfterFailure() {
        CompositeOrder order = createOrder(
                BookingType.FLIGHT,
                BookingType.HOTEL,
                BookingType.INSURANCE,
                BookingType.TRANSFER
        );
        BookingStep flightStep = order.getBookingSteps().get(0);
        BookingStep hotelStep = order.getBookingSteps().get(1);
        BookingStep insuranceStep = order.getBookingSteps().get(2);
        BookingStep transferStep = order.getBookingSteps().get(3);
        stubSuccessfulBooking(flightStep);
        stubFailedBooking(hotelStep);

        service.orchestrate(orderId);

        assertEquals(BookingStatus.CANCELLED, flightStep.getStatus());
        assertEquals(BookingStatus.FAILED, hotelStep.getStatus());
        assertEquals(BookingStatus.PENDING, insuranceStep.getStatus());
        assertEquals(BookingStatus.PENDING, transferStep.getStatus());
        assertTrue(clients.get(BookingType.INSURANCE).requests().isEmpty());
        assertTrue(clients.get(BookingType.TRANSFER).requests().isEmpty());
    }

    private PartialFailure orchestratePartialFailure() {
        CompositeOrder order = createOrder(
                BookingType.FLIGHT,
                BookingType.HOTEL,
                BookingType.INSURANCE,
                BookingType.TRANSFER
        );
        BookingStep flightStep = order.getBookingSteps().get(0);
        BookingStep hotelStep = order.getBookingSteps().get(1);
        BookingStep insuranceStep = order.getBookingSteps().get(2);
        BookingStep transferStep = order.getBookingSteps().get(3);
        UUID flightExternalId = stubSuccessfulBooking(flightStep);
        UUID hotelExternalId = stubSuccessfulBooking(hotelStep);
        stubExternalFailure(
                insuranceStep,
                HttpStatus.CONFLICT,
                ExternalBookingErrorType.RESOURCE_UNAVAILABLE
        );

        service.orchestrate(orderId);

        return new PartialFailure(
                order,
                flightStep,
                hotelStep,
                insuranceStep,
                transferStep,
                flightExternalId,
                hotelExternalId
        );
    }

    private CompensationFailure orchestrateCancellationFailure() {
        CompositeOrder order = createOrder(
                BookingType.FLIGHT,
                BookingType.HOTEL,
                BookingType.INSURANCE
        );
        BookingStep flightStep = order.getBookingSteps().get(0);
        BookingStep hotelStep = order.getBookingSteps().get(1);
        BookingStep insuranceStep = order.getBookingSteps().get(2);
        stubSuccessfulBooking(flightStep);
        stubSuccessfulBooking(hotelStep);
        stubExternalFailure(
                insuranceStep,
                HttpStatus.CONFLICT,
                ExternalBookingErrorType.RESOURCE_UNAVAILABLE
        );
        ExternalBookingException cancellationFailure = new ExternalBookingException(
                HttpStatus.NOT_FOUND,
                BookingType.HOTEL,
                ExternalBookingErrorType.NOT_FOUND,
                "External booking not found",
                "/api/v1/hotel/cancel",
                null
        );
        clients.get(BookingType.HOTEL).respondToCancellationWith(request -> {
            throw cancellationFailure;
        });

        ExternalBookingException thrown = assertThrows(
                ExternalBookingException.class,
                () -> service.orchestrate(orderId)
        );

        assertSame(cancellationFailure, thrown);
        assertEquals(1, thrown.getSuppressed().length);
        assertTrue(clients.get(BookingType.FLIGHT)
                .cancellationRequests().isEmpty());
        return new CompensationFailure(order, flightStep, hotelStep);
    }

    private CompositeOrder orchestrateExternalFailure(
            HttpStatus status,
            ExternalBookingErrorType errorType
    ) {
        CompositeOrder order = createOrder(BookingType.FLIGHT);
        stubExternalFailure(order.getBookingSteps().getFirst(), status, errorType);

        service.orchestrate(orderId);
        return order;
    }

    private void stubExternalFailure(
            BookingStep step,
            HttpStatus status,
            ExternalBookingErrorType errorType
    ) {
        clients.get(step.getType()).respondWith(request -> {
            throw new ExternalBookingException(
                    status,
                    step.getType(),
                    errorType,
                    "External booking failed",
                    "/api/v1/flight/book",
                    null
            );
        });
    }

    private CompositeOrder createOrder(BookingType... types) {
        CompositeOrder order = CompositeOrder.create();
        ReflectionTestUtils.setField(order, "id", orderId);

        for (BookingType type : types) {
            order.addBookingStep(type, UUID.randomUUID());
            BookingStep step = order.getBookingSteps().getLast();
            ReflectionTestUtils.setField(step, "id", UUID.randomUUID());
        }

        storedOrder = order;
        return order;
    }

    private UUID stubSuccessfulBooking(BookingStep step) {
        UUID externalBookingId = UUID.randomUUID();
        clients.get(step.getType()).respondWith(request -> new BookingResponse(
                externalBookingId,
                step.getId(),
                "CONFIRMED",
                Instant.parse("2026-10-17T10:00:00Z")
        ));
        return externalBookingId;
    }

    private RuntimeException stubFailedBooking(BookingStep step) {
        RuntimeException exception = new RuntimeException("Booking failed");
        clients.get(step.getType()).respondWith(request -> {
            throw exception;
        });
        return exception;
    }

    private CompositeOrderRepository createRepository() {
        return (CompositeOrderRepository) Proxy.newProxyInstance(
                CompositeOrderRepository.class.getClassLoader(),
                new Class<?>[]{CompositeOrderRepository.class},
                (proxy, method, arguments) -> {
                    if (method.getName().equals("findById")) {
                        return Optional.ofNullable(storedOrder);
                    }
                    throw new UnsupportedOperationException(method.getName());
                }
        );
    }

    private record PartialFailure(
            CompositeOrder order,
            BookingStep flightStep,
            BookingStep hotelStep,
            BookingStep insuranceStep,
            BookingStep transferStep,
            UUID flightExternalId,
            UUID hotelExternalId
    ) {
    }

    private record CompensationFailure(
            CompositeOrder order,
            BookingStep flightStep,
            BookingStep hotelStep
    ) {
    }

    private static class MockBookingClient implements BookingClient {

        private final BookingType type;
        private final List<BookingType> invocationOrder;
        private final List<BookingType> cancellationOrder;
        private final List<BookingRequest> requests = new ArrayList<>();
        private final List<CancelBookingRequest> cancellationRequests =
                new ArrayList<>();
        private Function<BookingRequest, BookingResponse> response;
        private Function<CancelBookingRequest, CancelBookingResponse>
                cancellationResponse;

        private MockBookingClient(
                BookingType type,
                List<BookingType> invocationOrder,
                List<BookingType> cancellationOrder
        ) {
            this.type = type;
            this.invocationOrder = invocationOrder;
            this.cancellationOrder = cancellationOrder;
            this.cancellationResponse = request -> new CancelBookingResponse(
                    request.bookingId(),
                    "CANCELLED",
                    Instant.parse("2026-10-17T11:00:00Z")
            );
        }

        @Override
        public BookingType type() {
            return type;
        }

        @Override
        public BookingResponse book(BookingRequest request) {
            invocationOrder.add(type);
            requests.add(request);
            if (response == null) {
                throw new IllegalStateException("Booking response is not configured");
            }
            return response.apply(request);
        }

        @Override
        public CancelBookingResponse cancel(CancelBookingRequest request) {
            cancellationOrder.add(type);
            cancellationRequests.add(request);
            return cancellationResponse.apply(request);
        }

        private void respondWith(Function<BookingRequest, BookingResponse> response) {
            this.response = response;
        }

        private void respondToCancellationWith(
                Function<CancelBookingRequest, CancelBookingResponse> response
        ) {
            this.cancellationResponse = response;
        }

        private List<BookingRequest> requests() {
            return requests;
        }

        private List<CancelBookingRequest> cancellationRequests() {
            return cancellationRequests;
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
