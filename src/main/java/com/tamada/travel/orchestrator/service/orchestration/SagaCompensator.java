package com.tamada.travel.orchestrator.service.orchestration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tamada.travel.orchestrator.client.BookingClient;
import com.tamada.travel.orchestrator.client.BookingClientRegistry;
import com.tamada.travel.orchestrator.client.dto.CancelBookingRequest;
import com.tamada.travel.orchestrator.client.dto.CancelBookingResponse;
import com.tamada.travel.orchestrator.exception.ExternalBookingException;
import com.tamada.travel.orchestrator.exception.InvalidBookingDataException;
import com.tamada.travel.orchestrator.exception.InvalidStateTransitionException;
import com.tamada.travel.orchestrator.exception.OrderNotFoundException;
import com.tamada.travel.orchestrator.model.BookingStatus;
import com.tamada.travel.orchestrator.model.BookingStep;
import com.tamada.travel.orchestrator.model.BookingType;
import com.tamada.travel.orchestrator.model.CompositeOrder;
import com.tamada.travel.orchestrator.model.OrderStatus;
import com.tamada.travel.orchestrator.repository.CompositeOrderRepository;
import com.tamada.travel.orchestrator.service.error.StepErrorMapper;
import com.tamada.travel.orchestrator.service.error.UserErrorMessageResolver;
import com.tamada.travel.orchestrator.service.sse.OrderSseService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Координатор компенсационных транзакций (Saga Compensator).
 * Отвечает за вызов внешних эндпоинтов /cancel в обратном порядке для подтвержденных шагов,
 * сохранение причин отмены, запись истории операций и отправку SSE-событий.
 */
@Component
@Slf4j
public class SagaCompensator {

    private final BookingClientRegistry clientRegistry;
    private final StepResponseValidator responseValidator;
    private final CompositeOrderRepository repository;
    private final TransactionTemplate transactionTemplate;
    private final UserErrorMessageResolver errorMessageResolver;
    private final OrderSseService sseService;
    private final StepErrorMapper stepErrorMapper;
    private final com.tamada.travel.orchestrator.observability.SagaMetrics metrics;
    private final com.tamada.travel.orchestrator.config.SagaCompensationProperties compensationProperties;
    private org.springframework.context.ApplicationEventPublisher eventPublisher;

    @Autowired
    public SagaCompensator(
            BookingClientRegistry clientRegistry,
            StepResponseValidator responseValidator,
            CompositeOrderRepository repository,
            TransactionTemplate transactionTemplate,
            @Autowired(required = false) UserErrorMessageResolver errorMessageResolver,
            @Autowired(required = false) OrderSseService sseService,
            @Autowired(required = false) StepErrorMapper stepErrorMapper,
            @Autowired(required = false) com.tamada.travel.orchestrator.observability.SagaMetrics metrics,
            @Autowired(required = false) org.springframework.context.ApplicationEventPublisher eventPublisher,
            @Autowired(required = false) com.tamada.travel.orchestrator.config.SagaCompensationProperties compensationProperties
    ) {
        this.clientRegistry = Objects.requireNonNull(clientRegistry, "clientRegistry must not be null");
        this.responseValidator = Objects.requireNonNull(responseValidator, "responseValidator must not be null");
        this.repository = Objects.requireNonNull(repository, "repository must not be null");
        this.transactionTemplate = Objects.requireNonNull(transactionTemplate, "transactionTemplate must not be null");
        this.errorMessageResolver = errorMessageResolver != null ? errorMessageResolver : new UserErrorMessageResolver();
        this.sseService = sseService;
        this.stepErrorMapper = stepErrorMapper != null
                ? stepErrorMapper
                : new StepErrorMapper(this.errorMessageResolver, new ObjectMapper());
        this.metrics = metrics;
        this.eventPublisher = eventPublisher;
        this.compensationProperties = compensationProperties;
    }

    public SagaCompensator(
            BookingClientRegistry clientRegistry,
            StepResponseValidator responseValidator,
            CompositeOrderRepository repository,
            TransactionTemplate transactionTemplate,
            @Autowired(required = false) UserErrorMessageResolver errorMessageResolver,
            @Autowired(required = false) OrderSseService sseService,
            @Autowired(required = false) StepErrorMapper stepErrorMapper,
            @Autowired(required = false) com.tamada.travel.orchestrator.observability.SagaMetrics metrics,
            @Autowired(required = false) org.springframework.context.ApplicationEventPublisher eventPublisher
    ) {
        this(clientRegistry, responseValidator, repository, transactionTemplate, errorMessageResolver, sseService, stepErrorMapper, metrics, eventPublisher, null);
    }

    public SagaCompensator(
            BookingClientRegistry clientRegistry,
            StepResponseValidator responseValidator,
            CompositeOrderRepository repository,
            TransactionTemplate transactionTemplate,
            UserErrorMessageResolver errorMessageResolver,
            OrderSseService sseService,
            StepErrorMapper stepErrorMapper,
            com.tamada.travel.orchestrator.observability.SagaMetrics metrics
    ) {
        this(clientRegistry, responseValidator, repository, transactionTemplate, errorMessageResolver, sseService, stepErrorMapper, metrics, null);
    }

    public SagaCompensator(
            BookingClientRegistry clientRegistry,
            StepResponseValidator responseValidator,
            CompositeOrderRepository repository,
            TransactionTemplate transactionTemplate,
            UserErrorMessageResolver errorMessageResolver,
            OrderSseService sseService,
            StepErrorMapper stepErrorMapper
    ) {
        this(clientRegistry, responseValidator, repository, transactionTemplate, errorMessageResolver, sseService, stepErrorMapper, null);
    }

    public SagaCompensator(
            BookingClientRegistry clientRegistry,
            StepResponseValidator responseValidator,
            CompositeOrderRepository repository,
            TransactionTemplate transactionTemplate,
            UserErrorMessageResolver errorMessageResolver,
            OrderSseService sseService,
            ObjectMapper objectMapper
    ) {
        this(
                clientRegistry,
                responseValidator,
                repository,
                transactionTemplate,
                errorMessageResolver,
                sseService,
                new StepErrorMapper(errorMessageResolver != null ? errorMessageResolver : new UserErrorMessageResolver(), objectMapper)
        );
    }

    public SagaCompensator(
            BookingClientRegistry clientRegistry,
            StepResponseValidator responseValidator,
            CompositeOrderRepository repository,
            TransactionTemplate transactionTemplate
    ) {
        this(clientRegistry, responseValidator, repository, transactionTemplate,
                new UserErrorMessageResolver(), null, (StepErrorMapper) null);
    }

    public CompositeOrder cancelConfirmedSteps(
            UUID orderId,
            boolean cancelOrderWithoutConfirmedSteps
    ) {
        return cancelConfirmedSteps(orderId, cancelOrderWithoutConfirmedSteps, null, null, null);
    }

    public void compensateSingleStep(
            UUID orderId,
            UUID stepId,
            String reason,
            String userMessage
    ) {
        CancellationCommand command = inTransaction(() -> {
            CompositeOrder order = findOrder(orderId);
            BookingStep step = order.getBookingSteps().stream()
                    .filter(s -> Objects.equals(s.getId(), stepId))
                    .findFirst()
                    .orElseThrow(() -> new InvalidStateTransitionException("Booking step not found: " + stepId));
            return toCancellationCommand(step);
        });

        cancelStep(orderId, command, null, userMessage, reason, userMessage);
    }

    public CompositeOrder cancelConfirmedSteps(
            UUID orderId,
            boolean cancelOrderWithoutConfirmedSteps,
            BookingType failedStepType,
            String failureReason,
            String failureUserMessage
    ) {
        List<CancellationCommand> commands = inTransaction(
                () -> startCancellation(orderId, cancelOrderWithoutConfirmedSteps)
        );

        if (commands.isEmpty() && !cancelOrderWithoutConfirmedSteps) {
            return findOrder(orderId);
        }

        ExternalBookingException firstException = null;
        for (CancellationCommand command : commands) {
            try {
                cancelStep(orderId, command, failedStepType, failureUserMessage);
            } catch (ExternalBookingException e) {
                if (compensationProperties != null && compensationProperties.continueOnFailure()) {
                    log.error("Compensation failed for step {} of order {}, continuing remaining steps due to continue-on-failure=true",
                            command.stepId(), orderId, e);
                    if (firstException == null) {
                        firstException = e;
                    }
                } else {
                    throw e;
                }
            }
        }

        if (firstException != null) {
            throw firstException;
        }

        return inTransaction(() -> {
            CompositeOrder order = findOrder(orderId);

            // Обрабатываем неначатые (PENDING) шаги, если это ручная отмена
            if (failedStepType == null) {
                for (BookingStep step : order.getBookingSteps()) {
                    if (step.getStatus() == BookingStatus.PENDING) {
                        String skipReason = errorMessageResolver.resolveManualCancellationReason(step.getType());
                        String skipUserMsg = errorMessageResolver.resolveManualCancellationUserMessage(step.getType());
                        step.markSkipped(skipReason, skipUserMsg);
                        order.recordOperation(step, step.getType(), "SKIP", "SKIPPED",
                                null, null, null, skipUserMsg, null, skipReason);
                    }
                }
            }

            String finalOrderFailureReason = failureReason != null ? failureReason : "Отменено пользователем";
            String finalOrderUserMessage = failureUserMessage != null
                    ? errorMessageResolver.resolveOrderFailureMessage(failedStepType, failureUserMessage)
                    : "Заказ успешно отменен пользователем.";

            order.cancel(finalOrderFailureReason, finalOrderUserMessage);

            order.recordOperation(
                    null,
                    null,
                    failedStepType != null ? "ORDER_CANCELLATION" : "MANUAL_CANCEL",
                    "CANCELLED",
                    null,
                    null,
                    finalOrderFailureReason,
                    finalOrderUserMessage,
                    null,
                    finalOrderFailureReason
            );

            if (sseService != null) {
                sseService.publishOrderCancelled(orderId, finalOrderFailureReason, finalOrderUserMessage);
            }

            if (eventPublisher != null) {
                eventPublisher.publishEvent(new com.tamada.travel.orchestrator.event.OrderStatusChangedEvent(
                        orderId,
                        OrderStatus.CANCELLED,
                        finalOrderFailureReason,
                        finalOrderUserMessage
                ));
            }

            return order;
        });
    }

    private List<CancellationCommand> startCancellation(
            UUID orderId,
            boolean cancelOrderWithoutConfirmedSteps
    ) {
        CompositeOrder order = findOrder(orderId);
        List<CancellationCommand> commands = order.getBookingSteps()
                .reversed()
                .stream()
                .filter(step -> step.getStatus() == BookingStatus.CONFIRMED
                        || step.getStatus() == BookingStatus.CANCELLING)
                .map(this::toCancellationCommand)
                .toList();

        if (!commands.isEmpty() || cancelOrderWithoutConfirmedSteps || order.getStatus() == OrderStatus.CANCELLING) {
            order.startCancelling();
        }
        return commands;
    }

    private CancellationCommand toCancellationCommand(BookingStep step) {
        String externalId = step.getExternalId();
        if (externalId == null || externalId.isBlank()) {
            throw new InvalidBookingDataException(
                    "External booking id is missing for step " + step.getId()
            );
        }

        try {
            return new CancellationCommand(
                    step.getId(),
                    step.getType(),
                    UUID.fromString(externalId)
            );
        } catch (IllegalArgumentException exception) {
            throw new InvalidBookingDataException(
                    "External booking id is not a UUID for step " + step.getId()
            );
        }
    }

    private void cancelStep(
            UUID orderId,
            CancellationCommand command,
            BookingType failedStepType,
            String failureUserMessage
    ) {
        cancelStep(orderId, command, failedStepType, failureUserMessage, null, null);
    }

    private void cancelStep(
            UUID orderId,
            CancellationCommand command,
            BookingType failedStepType,
            String failureUserMessage,
            String customReason,
            String customUserMessage
    ) {
        String compReason = customReason != null ? customReason : (failedStepType != null
                ? errorMessageResolver.resolveCompensationReason(command.type(), failedStepType, failureUserMessage)
                : errorMessageResolver.resolveManualCancellationReason(command.type()));

        String compUserMsg = customUserMessage != null ? customUserMessage : (failedStepType != null
                ? errorMessageResolver.resolveCompensationUserMessage(command.type(), failedStepType)
                : errorMessageResolver.resolveManualCancellationUserMessage(command.type()));

        inTransaction(() -> {
            BookingStep step = findBookingStep(orderId, command.stepId());
            step.startCancelling(compReason);
            return true;
        });

        if (sseService != null) {
            sseService.publishStepCancelled(
                    orderId,
                    command.stepId(),
                    command.type(),
                    BookingStatus.CANCELLING,
                    compReason,
                    compUserMsg
            );
        }

        try {
            CancelBookingResponse response = clientRegistry.cancel(
                    command.type(),
                    new CancelBookingRequest(command.externalBookingId())
            );
            responseValidator.validateCancellationResponse(command.externalBookingId(), response);
        } catch (ExternalBookingException exception) {
            log.warn(
                    "External cancellation failed: orderId={}, bookingStepId={}, "
                            + "bookingType={}, httpStatus={}, message={}",
                    orderId,
                    command.stepId(),
                    command.type(),
                    exception.getHttpStatus().value(),
                    exception.getMessage()
            );

            inTransaction(() -> {
                CompositeOrder order = findOrder(orderId);
                BookingStep step = findBookingStep(orderId, command.stepId());
                String detailsJson = stepErrorMapper.serializeException(exception);
                order.recordOperation(
                        step,
                        command.type(),
                        "COMPENSATION",
                        "FAILED",
                        exception.getHttpStatus().value(),
                        exception.getErrorType().name(),
                        exception.getMessage(),
                        "Не удалось отменить бронирование во внешнем сервисе: " + exception.getMessage(),
                        detailsJson,
                        compReason
                );
                return true;
            });

            throw exception;
        }

        inTransaction(() -> {
            CompositeOrder order = findOrder(orderId);
            BookingStep step = findBookingStep(orderId, command.stepId());
            step.cancel(compReason, compUserMsg);
            order.recordOperation(
                    step,
                    command.type(),
                    failedStepType != null ? "COMPENSATION" : "MANUAL_CANCEL",
                    "CANCELLED",
                    200,
                    null,
                    null,
                    compUserMsg,
                    null,
                    compReason
            );
            return true;
        });

        if (metrics != null) {
            metrics.incrementCompensation(command.type(), failedStepType == null);
        }

        if (sseService != null) {
            sseService.publishStepCancelled(
                    orderId,
                    command.stepId(),
                    command.type(),
                    BookingStatus.CANCELLED,
                    compReason,
                    compUserMsg
            );
        }

        if (eventPublisher != null) {
            eventPublisher.publishEvent(new com.tamada.travel.orchestrator.event.BookingStepCancelledEvent(
                    orderId,
                    command.stepId(),
                    command.type(),
                    BookingStatus.CANCELLED,
                    compReason,
                    compUserMsg
            ));
        }
    }

    private BookingStep findBookingStep(UUID orderId, UUID stepId) {
        return findOrder(orderId).getBookingSteps().stream()
                .filter(candidate -> Objects.equals(candidate.getId(), stepId))
                .findFirst()
                .orElseThrow(() -> new InvalidStateTransitionException(
                        "Booking step not found: " + stepId
                ));
    }

    private CompositeOrder findOrder(UUID orderId) {
        return repository.findById(orderId)
                .orElseThrow(() -> new OrderNotFoundException(orderId));
    }

    private <T> T inTransaction(Supplier<T> action) {
        return Objects.requireNonNull(
                transactionTemplate.execute(status -> action.get())
        );
    }

    public record CancellationCommand(
            UUID stepId,
            BookingType type,
            UUID externalBookingId
    ) {
    }
}
