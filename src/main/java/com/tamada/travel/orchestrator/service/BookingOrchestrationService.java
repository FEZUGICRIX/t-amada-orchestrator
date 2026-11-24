package com.tamada.travel.orchestrator.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tamada.travel.orchestrator.client.BookingClientRegistry;
import com.tamada.travel.orchestrator.config.SagaCompensationProperties;
import com.tamada.travel.orchestrator.dto.error.StepErrorDetailsResponse;
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
import com.tamada.travel.orchestrator.service.orchestration.SagaCompensator;
import com.tamada.travel.orchestrator.service.orchestration.SagaExecutionLock;
import com.tamada.travel.orchestrator.service.orchestration.SagaLock;
import com.tamada.travel.orchestrator.service.orchestration.StepExecutionResult;
import com.tamada.travel.orchestrator.service.orchestration.StepExecutor;
import com.tamada.travel.orchestrator.service.orchestration.StepResponseValidator;
import com.tamada.travel.orchestrator.service.sse.OrderSseService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
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
    private final SagaLock executionLock;
    private final UserErrorMessageResolver errorMessageResolver;
    private final OrderSseService sseService;
    private final StepErrorMapper stepErrorMapper;
    private final com.tamada.travel.orchestrator.observability.SagaMetrics metrics;
    private final java.util.concurrent.Executor taskExecutor;
    private boolean partialCompensationEnabled = true;
    private boolean parallelStepsEnabled = false;
    private org.springframework.context.ApplicationEventPublisher eventPublisher;

    @Autowired
    public BookingOrchestrationService(
            CompositeOrderRepository repository,
            TransactionTemplate transactionTemplate,
            StepExecutor stepExecutor,
            SagaCompensator compensator,
            SagaLock executionLock,
            @Autowired(required = false) UserErrorMessageResolver errorMessageResolver,
            @Autowired(required = false) OrderSseService sseService,
            @Autowired(required = false) StepErrorMapper stepErrorMapper,
            @Autowired(required = false) SagaCompensationProperties compensationProperties,
            @Autowired(required = false) com.tamada.travel.orchestrator.observability.SagaMetrics metrics,
            @Autowired(required = false) com.tamada.travel.orchestrator.config.SagaExecutionProperties executionProperties,
            @Autowired(required = false) org.springframework.context.ApplicationEventPublisher eventPublisher,
            @Autowired(required = false) @org.springframework.beans.factory.annotation.Qualifier("sagaTaskExecutor") java.util.concurrent.Executor taskExecutor
    ) {
        this.repository = Objects.requireNonNull(repository, "repository must not be null");
        this.transactionTemplate = Objects.requireNonNull(transactionTemplate, "transactionTemplate must not be null");
        this.stepExecutor = Objects.requireNonNull(stepExecutor, "stepExecutor must not be null");
        this.compensator = Objects.requireNonNull(compensator, "compensator must not be null");
        this.executionLock = Objects.requireNonNull(executionLock, "executionLock must not be null");
        this.errorMessageResolver = errorMessageResolver != null ? errorMessageResolver : new UserErrorMessageResolver();
        this.sseService = sseService;
        this.stepErrorMapper = stepErrorMapper != null
                ? stepErrorMapper
                : new StepErrorMapper(this.errorMessageResolver, new ObjectMapper());
        this.partialCompensationEnabled = compensationProperties != null && compensationProperties.partialCompensationEnabled();
        this.metrics = metrics;
        this.parallelStepsEnabled = executionProperties != null && executionProperties.parallelStepsEnabled();
        this.eventPublisher = eventPublisher;
        this.taskExecutor = taskExecutor != null ? taskExecutor : java.util.concurrent.ForkJoinPool.commonPool();
    }

    public BookingOrchestrationService(
            CompositeOrderRepository repository,
            TransactionTemplate transactionTemplate,
            StepExecutor stepExecutor,
            SagaCompensator compensator,
            SagaLock executionLock,
            UserErrorMessageResolver errorMessageResolver,
            OrderSseService sseService,
            StepErrorMapper stepErrorMapper,
            SagaCompensationProperties compensationProperties,
            com.tamada.travel.orchestrator.observability.SagaMetrics metrics
    ) {
        this(repository, transactionTemplate, stepExecutor, compensator, executionLock, errorMessageResolver, sseService, stepErrorMapper, compensationProperties, metrics, null, null, null);
    }

    public BookingOrchestrationService(
            CompositeOrderRepository repository,
            TransactionTemplate transactionTemplate,
            StepExecutor stepExecutor,
            SagaCompensator compensator,
            SagaLock executionLock,
            UserErrorMessageResolver errorMessageResolver,
            OrderSseService sseService,
            ObjectMapper objectMapper
    ) {
        this(
                repository,
                transactionTemplate,
                stepExecutor,
                compensator,
                executionLock,
                errorMessageResolver,
                sseService,
                new StepErrorMapper(errorMessageResolver != null ? errorMessageResolver : new UserErrorMessageResolver(), objectMapper),
                null,
                null,
                null,
                null,
                null
        );
    }

    /**
     * Конструктор для тестов, инициализирующий сагу с заданной фабрикой клиентов.
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
        StepErrorMapper errorMapper = new StepErrorMapper(resolver, mapper);
        this.stepExecutor = new StepExecutor(clientRegistry, validator, repository, transactionTemplate);
        this.compensator = new SagaCompensator(clientRegistry, validator, repository, transactionTemplate, resolver, null, errorMapper);
        this.executionLock = new SagaExecutionLock();
        this.errorMessageResolver = resolver;
        this.sseService = null;
        this.stepErrorMapper = errorMapper;
        this.partialCompensationEnabled = false;
        this.metrics = null;
        this.taskExecutor = java.util.concurrent.ForkJoinPool.commonPool();
    }

    public boolean isPartialCompensationEnabled() {
        return partialCompensationEnabled;
    }

    public void setPartialCompensationEnabled(boolean partialCompensationEnabled) {
        this.partialCompensationEnabled = partialCompensationEnabled;
    }

    public boolean isParallelStepsEnabled() {
        return parallelStepsEnabled;
    }

    public void setParallelStepsEnabled(boolean parallelStepsEnabled) {
        this.parallelStepsEnabled = parallelStepsEnabled;
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

    public CompositeOrder compensate(UUID orderId) {
        return executionLock.executeWithLock(orderId, () -> compensator.cancelConfirmedSteps(
                orderId,
                true,
                null,
                "Компенсация по расписанию (Compensation Scheduler)",
                "Заказ отменен в рамках плановой компенсации зависших отмен."
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
            } else if (order.getStatus() == OrderStatus.FAILED) {
                order.startProcessing();
                order.clearPendingStepsSkipInfo();
                order.recordOperation(
                        null,
                        null,
                        "ORDER_RESUMED",
                        "PROCESSING",
                        null,
                        null,
                        null,
                        "Возобновление бронирования заказа после модификации/замены услуг.",
                        null,
                        null
                );
            } else if (order.getStatus() == OrderStatus.COMPLETED && order.getBookingSteps().stream().anyMatch(s -> s.getStatus() == BookingStatus.PENDING)) {
                order.startProcessing();
                order.clearPendingStepsSkipInfo();
                order.recordOperation(
                        null,
                        null,
                        "ORDER_RESUMED",
                        "PROCESSING",
                        null,
                        null,
                        null,
                        "До-оркестрация добавленных услуг заказа.",
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

        if (eventPublisher != null) {
            eventPublisher.publishEvent(new com.tamada.travel.orchestrator.event.OrderStatusChangedEvent(orderId, OrderStatus.PROCESSING));
        }

        while (true) {
            if (Thread.currentThread().isInterrupted()) {
                throw new IllegalStateException("Saga execution interrupted for order " + orderId);
            }

            if (parallelStepsEnabled) {
                // Фазово-параллельное выполнение: если все авиабилеты подтверждены,
                // независимые наземные услуги (HOTEL, INSURANCE, TRANSFER) бронируются одновременно
                boolean hasPendingFlight = inTransaction(() -> {
                    CompositeOrder order = findOrder(orderId);
                    return order.getBookingSteps().stream()
                            .anyMatch(s -> s.getType() == BookingType.FLIGHT && s.getStatus() == BookingStatus.PENDING);
                });

                if (!hasPendingFlight) {
                    List<BookingStepCommand> parallelBatch = inTransaction(() -> {
                        CompositeOrder order = findOrder(orderId);
                        List<BookingStepCommand> batch = new ArrayList<>();
                        for (BookingStep s : order.getBookingSteps()) {
                            if (s.getStatus() == BookingStatus.PENDING) {
                                s.startProcessing();
                                batch.add(new BookingStepCommand(s.getId(), s.getType(), s.getResourceId()));
                            }
                        }
                        return batch;
                    });

                    if (parallelBatch.size() > 1) {
                        boolean batchFailed = executeParallelBatch(orderId, parallelBatch);
                        if (batchFailed) {
                            return findOrder(orderId);
                        }
                        continue;
                    } else if (parallelBatch.size() == 1) {
                        inTransaction(() -> {
                            CompositeOrder order = findOrder(orderId);
                            order.findBookingStep(parallelBatch.get(0).bookingReference()).ifPresent(BookingStep::resetToPending);
                            return true;
                        });
                    }
                }
            }

            Optional<BookingStepCommand> nextStep = inTransaction(() -> startNextStep(orderId));

            if (nextStep.isEmpty()) {
                CompositeOrder completedOrder = inTransaction(() -> completeOrder(orderId));
                if (metrics != null) {
                    metrics.incrementOrderCounter(OrderStatus.COMPLETED);
                    metrics.recordOrderDuration(OrderStatus.COMPLETED, java.time.Duration.between(completedOrder.getCreatedAt(), java.time.Instant.now()));
                }
                if (sseService != null) {
                    sseService.publishOrderCompleted(orderId);
                }
                if (eventPublisher != null) {
                    eventPublisher.publishEvent(new com.tamada.travel.orchestrator.event.OrderStatusChangedEvent(orderId, OrderStatus.COMPLETED));
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
                if (metrics != null) {
                    metrics.incrementStepExecution(step.type(), BookingStatus.CONFIRMED);
                }
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

                if (eventPublisher != null) {
                    eventPublisher.publishEvent(new com.tamada.travel.orchestrator.event.BookingStepStatusChangedEvent(
                            orderId, step.bookingReference(), step.type(), BookingStatus.CONFIRMED, success.externalBookingId().toString()));
                }
            } else if (result instanceof StepExecutionResult.Failure failure) {
                if (metrics != null) {
                    metrics.incrementStepExecution(step.type(), BookingStatus.FAILED);
                }
                handleBookingFailure(orderId, step, failure.exception());
                return findOrder(orderId);
            }
        }
    }

    private boolean executeParallelBatch(UUID orderId, List<BookingStepCommand> batch) {
        if (sseService != null) {
            for (BookingStepCommand step : batch) {
                sseService.publishStepStatus(orderId, step.bookingReference(), step.type(), BookingStatus.PROCESSING, null);
            }
        }

        List<CompletableFuture<StepBatchOutcome>> futures = batch.stream()
                .map(step -> CompletableFuture.supplyAsync(() -> {
                    StepExecutionResult res = stepExecutor.executeStep(
                            orderId,
                            step.bookingReference(),
                            step.type(),
                            step.resourceId()
                    );
                    return new StepBatchOutcome(step, res);
                }, taskExecutor))
                .toList();

        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

        BookingStepCommand firstFailedStep = null;
        RuntimeException firstException = null;

        for (var future : futures) {
            StepBatchOutcome outcome = future.join();
            BookingStepCommand step = outcome.step();
            StepExecutionResult result = outcome.result();

            if (result instanceof StepExecutionResult.Success success) {
                if (metrics != null) {
                    metrics.incrementStepExecution(step.type(), BookingStatus.CONFIRMED);
                }
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

                if (eventPublisher != null) {
                    eventPublisher.publishEvent(new com.tamada.travel.orchestrator.event.BookingStepStatusChangedEvent(
                            orderId, step.bookingReference(), step.type(), BookingStatus.CONFIRMED, success.externalBookingId().toString()));
                }
            } else if (result instanceof StepExecutionResult.Failure failure) {
                if (metrics != null) {
                    metrics.incrementStepExecution(step.type(), BookingStatus.FAILED);
                }
                if (firstFailedStep == null) {
                    firstFailedStep = step;
                    firstException = failure.exception();
                }
            }
        }

        if (firstFailedStep != null) {
            handleBookingFailure(orderId, firstFailedStep, firstException);
            return true;
        }

        return false;
    }

    private record StepBatchOutcome(BookingStepCommand step, StepExecutionResult result) {
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
        StepErrorMapper.StepErrorInfo errorInfo = stepErrorMapper.mapBookingError(stepCommand.type(), bookingFailure);
        final Integer httpStatus = errorInfo.httpStatus();
        final String errorCode = errorInfo.errorCode();
        final String technicalMessage = errorInfo.technicalMessage();
        final String userMessage = errorInfo.userMessage();
        final StepErrorDetailsResponse errorDetails = errorInfo.details();
        final String detailsJson = errorInfo.serializedDetailsJson();

        List<SkippedStepInfo> skippedSteps = inTransaction(() -> {
            CompositeOrder order = findOrder(orderId);
            order.failCurrentBookingStep(errorCode, technicalMessage, userMessage, detailsJson);

            BookingStep failedStep = order.getBookingSteps().stream()
                    .filter(s -> Objects.equals(s.getId(), stepCommand.bookingReference()))
                    .findFirst()
                    .orElse(null);

            order.recordOperation(
                    failedStep,
                    stepCommand.type(),
                    "BOOK",
                    "FAILED",
                    httpStatus,
                    errorCode,
                    technicalMessage,
                    userMessage,
                    detailsJson,
                    null
            );

            // Обрабатываем все оставшиеся PENDING шаги, фиксируя причины их отмены/пропуска
            List<SkippedStepInfo> skipped = new ArrayList<>();
            for (BookingStep step : order.getBookingSteps()) {
                if (step.getStatus() == BookingStatus.PENDING) {
                    String skipReason = errorMessageResolver.resolveSkippedStepReason(step.getType(), stepCommand.type(), userMessage);
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

        if (eventPublisher != null) {
            eventPublisher.publishEvent(new com.tamada.travel.orchestrator.event.BookingStepFailedEvent(
                    orderId,
                    stepCommand.bookingReference(),
                    stepCommand.type(),
                    errorCode,
                    httpStatus,
                    technicalMessage,
                    userMessage,
                    errorDetails
            ));
            for (SkippedStepInfo skipped : skippedSteps) {
                eventPublisher.publishEvent(new com.tamada.travel.orchestrator.event.BookingStepCancelledEvent(
                        orderId,
                        skipped.stepId(),
                        skipped.type(),
                        skipped.status(),
                        skipped.cancellationReason(),
                        skipped.userMessage()
                ));
            }
        }

        boolean isHotel = stepCommand.type() == BookingType.HOTEL;
        boolean preserveConfirmed = partialCompensationEnabled && isHotel;

        if (preserveConfirmed) {
            log.info("Partial compensation active: preserving confirmed bookings after {} failure for order {}",
                    stepCommand.type(), orderId);
            inTransaction(() -> {
                CompositeOrder order = findOrder(orderId);
                order.recordOperation(
                        null,
                        null,
                        "ORDER_FAILED",
                        "FAILED",
                        null,
                        errorCode,
                        technicalMessage,
                        "Сбой бронирования отеля. Заказ ожидает выбора альтернативного отеля. Выкупленные билеты сохранены.",
                        detailsJson,
                        null
                );
                return true;
            });
            if (sseService != null) {
                sseService.publishOrderStatus(orderId, OrderStatus.FAILED);
            }
            if (eventPublisher != null) {
                eventPublisher.publishEvent(new com.tamada.travel.orchestrator.event.OrderStatusChangedEvent(
                        orderId, OrderStatus.FAILED, technicalMessage, userMessage));
            }
            return;
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
