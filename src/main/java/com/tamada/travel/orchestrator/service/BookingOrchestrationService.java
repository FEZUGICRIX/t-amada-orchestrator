package com.tamada.travel.orchestrator.service;

import com.tamada.travel.orchestrator.client.BookingClientRegistry;
import com.tamada.travel.orchestrator.exception.OrderNotFoundException;
import com.tamada.travel.orchestrator.model.BookingType;
import com.tamada.travel.orchestrator.model.CompositeOrder;
import com.tamada.travel.orchestrator.repository.CompositeOrderRepository;
import com.tamada.travel.orchestrator.service.orchestration.SagaCompensator;
import com.tamada.travel.orchestrator.service.orchestration.SagaExecutionLock;
import com.tamada.travel.orchestrator.service.orchestration.StepExecutionResult;
import com.tamada.travel.orchestrator.service.orchestration.StepExecutor;
import com.tamada.travel.orchestrator.service.orchestration.StepResponseValidator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Saga Orchestrator для составного заказа путешествия.
 * Управляет жизненным циклом саги, последовательным выполнением прямых шагов (через {@link StepExecutor})
 * и компенсацией при сбоях (через {@link SagaCompensator}).
 * Предотвращает race conditions с помощью {@link SagaExecutionLock}.
 */
@Service
@Slf4j
public class BookingOrchestrationService {

    private final CompositeOrderRepository repository;
    private final TransactionTemplate transactionTemplate;
    private final StepExecutor stepExecutor;
    private final SagaCompensator compensator;
    private final SagaExecutionLock executionLock;

    @Autowired
    public BookingOrchestrationService(
            CompositeOrderRepository repository,
            TransactionTemplate transactionTemplate,
            StepExecutor stepExecutor,
            SagaCompensator compensator,
            SagaExecutionLock executionLock
    ) {
        this.repository = Objects.requireNonNull(repository, "repository must not be null");
        this.transactionTemplate = Objects.requireNonNull(transactionTemplate, "transactionTemplate must not be null");
        this.stepExecutor = Objects.requireNonNull(stepExecutor, "stepExecutor must not be null");
        this.compensator = Objects.requireNonNull(compensator, "compensator must not be null");
        this.executionLock = Objects.requireNonNull(executionLock, "executionLock must not be null");
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
        this.stepExecutor = new StepExecutor(clientRegistry, validator, repository, transactionTemplate);
        this.compensator = new SagaCompensator(clientRegistry, validator, repository, transactionTemplate);
        this.executionLock = new SagaExecutionLock();
    }

    public CompositeOrder orchestrate(UUID orderId) {
        return executionLock.executeWithLock(orderId, () -> executeSaga(orderId));
    }

    public CompositeOrder cancel(UUID orderId) {
        return executionLock.executeWithLock(orderId, () -> compensator.cancelConfirmedSteps(orderId, true));
    }

    private CompositeOrder executeSaga(UUID orderId) {
        inTransaction(() -> {
            findOrder(orderId).startProcessing();
            return true;
        });

        while (true) {
            if (Thread.currentThread().isInterrupted()) {
                throw new IllegalStateException("Saga execution interrupted for order " + orderId);
            }

            Optional<BookingStepCommand> nextStep = inTransaction(() -> startNextStep(orderId));

            if (nextStep.isEmpty()) {
                return inTransaction(() -> completeOrder(orderId));
            }

            BookingStepCommand step = nextStep.orElseThrow();
            StepExecutionResult result = stepExecutor.executeStep(
                    orderId,
                    step.bookingReference(),
                    step.type(),
                    step.resourceId()
            );

            if (result instanceof StepExecutionResult.Failure failure) {
                handleBookingFailure(orderId, failure.exception());
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

    private void handleBookingFailure(UUID orderId, RuntimeException bookingFailure) {
        failCurrentStep(orderId);
        try {
            compensator.cancelConfirmedSteps(orderId, true);
        } catch (RuntimeException compensationFailure) {
            compensationFailure.addSuppressed(bookingFailure);
            throw compensationFailure;
        }
    }

    private void failCurrentStep(UUID orderId) {
        inTransaction(() -> {
            findOrder(orderId).failCurrentBookingStep();
            return true;
        });
    }

    private CompositeOrder completeOrder(UUID orderId) {
        CompositeOrder order = findOrder(orderId);
        order.complete();
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
}
