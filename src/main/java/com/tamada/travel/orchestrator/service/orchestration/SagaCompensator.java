package com.tamada.travel.orchestrator.service.orchestration;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tamada.travel.orchestrator.client.BookingClient;
import com.tamada.travel.orchestrator.client.BookingClientRegistry;
import com.tamada.travel.orchestrator.client.dto.CancelBookingRequest;
import com.tamada.travel.orchestrator.client.dto.CancelBookingResponse;
import com.tamada.travel.orchestrator.dto.error.StepErrorDetailsResponse;
import com.tamada.travel.orchestrator.exception.ExternalBookingException;
import com.tamada.travel.orchestrator.exception.InvalidBookingDataException;
import com.tamada.travel.orchestrator.exception.InvalidStateTransitionException;
import com.tamada.travel.orchestrator.exception.OrderNotFoundException;
import com.tamada.travel.orchestrator.model.BookingStatus;
import com.tamada.travel.orchestrator.model.BookingStep;
import com.tamada.travel.orchestrator.model.BookingType;
import com.tamada.travel.orchestrator.model.CompositeOrder;
import com.tamada.travel.orchestrator.repository.CompositeOrderRepository;
import com.tamada.travel.orchestrator.service.error.UserErrorMessageResolver;
import com.tamada.travel.orchestrator.service.sse.OrderSseService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
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
    private final ObjectMapper objectMapper;

    @Autowired
    public SagaCompensator(
            BookingClientRegistry clientRegistry,
            StepResponseValidator responseValidator,
            CompositeOrderRepository repository,
            TransactionTemplate transactionTemplate,
            @Autowired(required = false) UserErrorMessageResolver errorMessageResolver,
            @Autowired(required = false) OrderSseService sseService,
            @Autowired(required = false) ObjectMapper objectMapper
    ) {
        this.clientRegistry = clientRegistry;
        this.responseValidator = responseValidator;
        this.repository = repository;
        this.transactionTemplate = transactionTemplate;
        this.errorMessageResolver = errorMessageResolver != null ? errorMessageResolver : new UserErrorMessageResolver();
        this.sseService = sseService;
        this.objectMapper = objectMapper != null ? objectMapper : new ObjectMapper();
    }

    public SagaCompensator(
            BookingClientRegistry clientRegistry,
            StepResponseValidator responseValidator,
            CompositeOrderRepository repository,
            TransactionTemplate transactionTemplate
    ) {
        this(clientRegistry, responseValidator, repository, transactionTemplate,
                new UserErrorMessageResolver(), null, new ObjectMapper());
    }

    public CompositeOrder cancelConfirmedSteps(
            UUID orderId,
            boolean cancelOrderWithoutConfirmedSteps
    ) {
        return cancelConfirmedSteps(orderId, cancelOrderWithoutConfirmedSteps, null, null, null);
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

        for (CancellationCommand command : commands) {
            cancelStep(orderId, command, failedStepType, failureUserMessage);
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
                .filter(step -> step.getStatus() == BookingStatus.CONFIRMED)
                .map(this::toCancellationCommand)
                .toList();

        if (!commands.isEmpty() || cancelOrderWithoutConfirmedSteps) {
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
        String compReason = failedStepType != null
                ? errorMessageResolver.resolveCompensationReason(command.type(), failedStepType, failureUserMessage)
                : errorMessageResolver.resolveManualCancellationReason(command.type());

        String compUserMsg = failedStepType != null
                ? errorMessageResolver.resolveCompensationUserMessage(command.type(), failedStepType)
                : errorMessageResolver.resolveManualCancellationUserMessage(command.type());

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
            BookingClient client = clientRegistry.getClient(command.type());
            CancelBookingResponse response = client.cancel(
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
                String detailsJson = serializeErrorDetails(exception);
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

    private String serializeErrorDetails(ExternalBookingException exception) {
        if (exception == null) {
            return null;
        }
        try {
            StepErrorDetailsResponse details = new StepErrorDetailsResponse(
                    exception.getHttpStatus().value(),
                    exception.getErrorTitle(),
                    exception.getSupplierMessage(),
                    exception.getPath(),
                    exception.getSupplierTimestamp() != null ? exception.getSupplierTimestamp() : Instant.now().toString()
            );
            return objectMapper.writeValueAsString(details);
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    public record CancellationCommand(
            UUID stepId,
            BookingType type,
            UUID externalBookingId
    ) {
    }
}
