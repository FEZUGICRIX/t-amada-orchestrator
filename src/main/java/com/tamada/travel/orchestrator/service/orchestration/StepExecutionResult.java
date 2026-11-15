package com.tamada.travel.orchestrator.service.orchestration;

import java.util.UUID;

/**
 * Результат выполнения отдельного шага бронирования в Саге.
 */
public sealed interface StepExecutionResult {

    record Success(UUID externalBookingId) implements StepExecutionResult {
    }

    record Failure(RuntimeException exception) implements StepExecutionResult {
    }

    default boolean isSuccess() {
        return this instanceof Success;
    }

    default boolean isFailure() {
        return this instanceof Failure;
    }
}
