package com.tamada.travel.orchestrator.service.orchestration;

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
import com.tamada.travel.orchestrator.repository.CompositeOrderRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Координатор компенсационных транзакций (Saga Compensator).
 * Отвечает за вызов внешних эндпоинтов /cancel в обратном порядке для подтвержденных шагов
 * и управление статусами CANCELLING -> CANCELLED.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SagaCompensator {

    private final BookingClientRegistry clientRegistry;
    private final StepResponseValidator responseValidator;
    private final CompositeOrderRepository repository;
    private final TransactionTemplate transactionTemplate;

    public CompositeOrder cancelConfirmedSteps(
            UUID orderId,
            boolean cancelOrderWithoutConfirmedSteps
    ) {
        List<CancellationCommand> commands = inTransaction(
                () -> startCancellation(orderId, cancelOrderWithoutConfirmedSteps)
        );

        if (commands.isEmpty() && !cancelOrderWithoutConfirmedSteps) {
            return findOrder(orderId);
        }

        for (CancellationCommand command : commands) {
            cancelStep(orderId, command);
        }

        return inTransaction(() -> {
            CompositeOrder order = findOrder(orderId);
            order.cancel();
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

    private void cancelStep(UUID orderId, CancellationCommand command) {
        inTransaction(() -> {
            findBookingStep(orderId, command.stepId()).startCancelling();
            return true;
        });

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
            throw exception;
        }

        inTransaction(() -> {
            findBookingStep(orderId, command.stepId()).cancel();
            return true;
        });
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
