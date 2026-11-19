package com.tamada.travel.orchestrator.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tamada.travel.orchestrator.client.BookingClientRegistry;
import com.tamada.travel.orchestrator.dto.error.StepErrorDetailsResponse;
import com.tamada.travel.orchestrator.exception.ExternalBookingException;
import com.tamada.travel.orchestrator.exception.InvalidStateTransitionException;
import com.tamada.travel.orchestrator.exception.OrderNotFoundException;
import com.tamada.travel.orchestrator.model.BookingStatus;
import com.tamada.travel.orchestrator.model.BookingStep;
import com.tamada.travel.orchestrator.model.BookingType;
import com.tamada.travel.orchestrator.model.CompositeOrder;
import com.tamada.travel.orchestrator.model.OrderStatus;
import com.tamada.travel.orchestrator.repository.CompositeOrderRepository;
import com.tamada.travel.orchestrator.service.error.UserErrorMessageResolver;
import com.tamada.travel.orchestrator.service.orchestration.SagaCompensator;
import com.tamada.travel.orchestrator.service.orchestration.SagaExecutionLock;
import com.tamada.travel.orchestrator.service.orchestration.StepExecutionResult;
import com.tamada.travel.orchestrator.service.orchestration.StepExecutor;
import com.tamada.travel.orchestrator.service.orchestration.StepResponseValidator;
import com.tamada.travel.orchestrator.service.sse.OrderSseService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Saga Orchestrator для составного заказа путешествия.
 * Управляет жизненным циклом саги, последовательным выполнением прямых шагов (через {@link StepExecutor}),
 * фиксацией ошибок поставщиков, записью истории операций, компенсацией (через {@link SagaCompensator})
 * и рассылкой событий через {@link OrderSseService}.
 */
@Service
@Slf4j
public class BookingOrchestrationService {

    private final CompositeOrderRepository repository;
    private final TransactionTemplate transactionTemplate;
    private final StepExecutor stepExecutor;
    private final SagaCompensator compensator;
    private final SagaExecutionLock executionLock;
    private final UserErrorMessageResolver errorMessageResolver;
    private final OrderSseService sseService;
    private final ObjectMapper objectMapper;

    @Autowired
    public BookingOrchestrationService(
            CompositeOrderRepository repository,
            TransactionTemplate transactionTemplate,
            StepExecutor stepExecutor,
            SagaCompensator compensator,
            SagaExecutionLock executionLock,
            @Autowired(required = false) UserErrorMessageResolver errorMessageResolver,
            @Autowired(required = false) OrderSseService sseService,
            @Autowired(required = false) ObjectMapper objectMapper
    ) {
        this.repository = Objects.requireNonNull(repository, "repository must not be null");
        this.transactionTemplate = Objects.requireNonNull(transactionTemplate, "transactionTemplate must not be null");
        this.stepExecutor = Objects.requireNonNull(stepExecutor, "stepExecutor must not be null");
        this.compensator = Objects.requireNonNull(compensator, "compensator must not be null");
        this.executionLock = Objects.requireNonNull(executionLock, "executionLock must not be null");
        this.errorMessageResolver = errorMessageResolver != null ? errorMessageResolver : new UserErrorMessageResolver();
        this.sseService = sseService;
        this.objectMapper = objectMapper != null ? objectMapper : new ObjectMapper();
    }

    /**
     * Конструктор обратной совместимости для существующих тестов, где зависимости не инжектятся через Spring.
     */
    public BookingOrchestrationService(
            CompositeOrderRepository repository,
            BookingClientRegistry clientRegistry,
            TransactionTemplate transactionTemplate
    ) {
        this.repository = Objects.requireNonNull(repository, "repository must not be null");
        this.transactionTemplate = Objects.requireNonNull(transactionTemplate, "transactionTemplate must not be null");
        StepResponseValidator validator = new StepResponseValidator();
        UserErrorMessageResolver resolver = new UserErrorMessageResolver();
        ObjectMapper mapper = new ObjectMapper();
        this.stepExecutor = new StepExecutor(clientRegistry, validator, repository, transactionTemplate);
        this.compensator = new SagaCompensator(clientRegistry, validator, repository, transactionTemplate, resolver, null, mapper);
        this.executionLock = new SagaExecutionLock();
        this.errorMessageResolver = resolver;
        this.sseService = null;
        this.objectMapper = mapper;
    }

    public CompositeOrder orchestrate(UUID orderId) {
        return executionLock.executeWithLock(orderId, () -> executeSaga(orderId));
    }

    public CompositeOrder recover(UUID orderId) {
        return executionLock.executeWithLock(orderId, () -> executeSaga(orderId));
    }

    public CompositeOrder cancel(UUID orderId) {
        return executionLock.executeWithLock(orderId, () -> compensator.cancelConfirmedSteps(
                orderId,
                true,
                null,
                "Отменено пользователем",
                "Заказ успешно отменен пользователем."
        ));
    }

    private CompositeOrder executeSaga(UUID orderId) {
        inTransaction(() -> {
            CompositeOrder order = findOrder(orderId);
            if (order.getStatus() == OrderStatus.NEW) {
                order.startProcessing();
                order.recordOperation(
                        null,
                        null,
                        "ORDER_PROCESSING",
                        "PROCESSING",
                        null,
                        null,
                        null,
                        "Начало процесса бронирования услуг заказа.",
                        null,
                        null
                );
            } else if (order.getStatus() == OrderStatus.PROCESSING) {
                order.resetProcessingStepsToPending();
                order.recordOperation(
                        null,
                        null,
                        "ORDER_RECOVERY",
                        "PROCESSING",
                        null,
                        null,
                        null,
                        "Возобновление процесса бронирования зависшего заказа.",
                        null,
                        null
                );
            } else {
                throw new InvalidStateTransitionException(
                        "Cannot start or resume order processing from status " + order.getStatus()
                );
            }
            return true;
        });

        if (sseService != null) {
            sseService.publishOrderStatus(orderId, OrderStatus.PROCESSING);
        }

        while (true) {
            if (Thread.currentThread().isInterrupted()) {
                throw new IllegalStateException("Saga execution interrupted for order " + orderId);
            }

            Optional<BookingStepCommand> nextStep = inTransaction(() -> startNextStep(orderId));

            if (nextStep.isEmpty()) {
                CompositeOrder completedOrder = inTransaction(() -> completeOrder(orderId));
                if (sseService != null) {
                    sseService.publishOrderCompleted(orderId);
                }
                return completedOrder;
            }

            BookingStepCommand step = nextStep.orElseThrow();

            if (sseService != null) {
                sseService.publishStepStatus(orderId, step.bookingReference(), step.type(), BookingStatus.PROCESSING, null);
            }

            StepExecutionResult result = stepExecutor.executeStep(
                    orderId,
                    step.bookingReference(),
                    step.type(),
                    step.resourceId()
            );

            if (result instanceof StepExecutionResult.Success success) {
                inTransaction(() -> {
                    CompositeOrder order = findOrder(orderId);
                    BookingStep bookingStep = findBookingStep(order, step.bookingReference());
                    order.recordOperation(
                            bookingStep,
                            step.type(),
                            "BOOK",
                            "CONFIRMED",
                            200,
                            null,
                            null,
                            "Услуга (" + errorMessageResolver.formatServiceName(step.type()) + ") успешно подтверждена внешним сервисом.",
                            null,
                            null
                    );
                    return true;
                });

                if (sseService != null) {
                    sseService.publishStepStatus(orderId, step.bookingReference(), step.type(), BookingStatus.CONFIRMED, success.externalBookingId().toString());
                }
            } else if (result instanceof StepExecutionResult.Failure failure) {
                handleBookingFailure(orderId, step, failure.exception());
                return findOrder(orderId);
            }
        }
    }

    private Optional<BookingStepCommand> startNextStep(UUID orderId) {
        return findOrder(orderId)
                .startNextBookingStep()
                .map(step -> new BookingStepCommand(
                        step.getId(),
                        step.getType(),
                        step.getResourceId()
                ));
    }

    private void handleBookingFailure(
            UUID orderId,
            BookingStepCommand stepCommand,
            RuntimeException bookingFailure
    ) {
        Integer httpStatus = null;
        String errorCode = "SYSTEM_FAILURE";
        String technicalMessage = bookingFailure.getMessage();
        String userMessage;
        StepErrorDetailsResponse errorDetails;

        if (bookingFailure instanceof ExternalBookingException extEx) {
            httpStatus = extEx.getHttpStatus().value();
            errorCode = extEx.getErrorType().name();
            technicalMessage = extEx.getMessage();
            userMessage = extEx.getUserFriendlyMessage() != null
                    ? extEx.getUserFriendlyMessage()
                    : errorMessageResolver.resolveBookingErrorMessage(extEx.getService(), extEx.getErrorType(), httpStatus, extEx.getMessage());
            errorDetails = new StepErrorDetailsResponse(
                    httpStatus,
                    extEx.getErrorTitle(),
                    extEx.getSupplierMessage(),
                    extEx.getPath(),
                    extEx.getSupplierTimestamp() != null ? extEx.getSupplierTimestamp() : Instant.now().toString()
            );
        } else {
            httpStatus = 500;
            errorCode = "SYSTEM_FAILURE";
            userMessage = "Сбой при бронировании услуги (" + errorMessageResolver.formatServiceName(stepCommand.type()) + "): " + bookingFailure.getMessage();
            errorDetails = new StepErrorDetailsResponse(
                    500,
                    "Internal Error",
                    bookingFailure.getMessage(),
                    null,
                    Instant.now().toString()
            );
        }

        String detailsJson = serializeErrorDetails(errorDetails);
        final String finalUserMessage = userMessage;
        final String finalErrorCode = errorCode;
        final Integer finalHttpStatus = httpStatus;
        final String finalTechnicalMessage = technicalMessage;

        List<SkippedStepInfo> skippedSteps = inTransaction(() -> {
            CompositeOrder order = findOrder(orderId);
            order.failCurrentBookingStep(finalErrorCode, finalTechnicalMessage, finalUserMessage, detailsJson);

            BookingStep failedStep = order.getBookingSteps().stream()
                    .filter(s -> Objects.equals(s.getId(), stepCommand.bookingReference()))
                    .findFirst()
                    .orElse(null);

            order.recordOperation(
                    failedStep,
                    stepCommand.type(),
                    "BOOK",
                    "FAILED",
                    finalHttpStatus,
                    finalErrorCode,
                    finalTechnicalMessage,
                    finalUserMessage,
                    detailsJson,
                    null
            );

            // Обрабатываем все оставшиеся PENDING шаги, фиксируя причины их отмены/пропуска
            List<SkippedStepInfo> skipped = new ArrayList<>();
            for (BookingStep step : order.getBookingSteps()) {
                if (step.getStatus() == BookingStatus.PENDING) {
                    String skipReason = errorMessageResolver.resolveSkippedStepReason(step.getType(), stepCommand.type(), finalUserMessage);
                    String skipUserMsg = errorMessageResolver.resolveSkippedStepUserMessage(step.getType(), stepCommand.type());
                    step.markSkipped(skipReason, skipUserMsg);

                    order.recordOperation(
                            step,
                            step.getType(),
                            "SKIP",
                            "SKIPPED",
                            null,
                            null,
                            null,
                            skipUserMsg,
                            null,
                            skipReason
                    );

                    skipped.add(new SkippedStepInfo(step.getId(), step.getType(), step.getStatus(), skipReason, skipUserMsg));
                }
            }

            return skipped;
        });

        if (sseService != null) {
            sseService.publishStepFailed(
                    orderId,
                    stepCommand.bookingReference(),
                    stepCommand.type(),
                    errorCode,
                    httpStatus,
                    technicalMessage,
                    userMessage,
                    errorDetails
            );

            for (SkippedStepInfo skipped : skippedSteps) {
                sseService.publishStepCancelled(
                        orderId,
                        skipped.stepId(),
                        skipped.type(),
                        skipped.status(),
                        skipped.cancellationReason(),
                        skipped.userMessage()
                );
            }
        }

        String orderFailureReason = "Сбой бронирования шага " + stepCommand.type() + ": " + technicalMessage;

        try {
            compensator.cancelConfirmedSteps(
                    orderId,
                    true,
                    stepCommand.type(),
                    orderFailureReason,
                    userMessage
            );
        } catch (RuntimeException compensationFailure) {
            compensationFailure.addSuppressed(bookingFailure);
            throw compensationFailure;
        }
    }

    private BookingStep findBookingStep(CompositeOrder order, UUID stepId) {
        return order.getBookingSteps().stream()
                .filter(candidate -> Objects.equals(candidate.getId(), stepId))
                .findFirst()
                .orElse(null);
    }

    private CompositeOrder completeOrder(UUID orderId) {
        CompositeOrder order = findOrder(orderId);
        order.complete();
        order.recordOperation(
                null,
                null,
                "ORDER_COMPLETED",
                "COMPLETED",
                null,
                null,
                null,
                "Все услуги заказа успешно забронированы.",
                null,
                null
        );
        return order;
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

    private String serializeErrorDetails(StepErrorDetailsResponse details) {
        if (details == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(details);
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    private record BookingStepCommand(
            UUID bookingReference,
            BookingType type,
            UUID resourceId
    ) {
    }

    private record SkippedStepInfo(
            UUID stepId,
            BookingType type,
            BookingStatus status,
            String cancellationReason,
            String userMessage
    ) {
    }
}
