package com.tamada.travel.orchestrator.service;

import com.tamada.travel.orchestrator.client.BookingClient;
import com.tamada.travel.orchestrator.client.BookingClientRegistry;
import com.tamada.travel.orchestrator.client.dto.BookingRequest;
import com.tamada.travel.orchestrator.client.dto.BookingResponse;
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
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

@Service
@RequiredArgsConstructor
@Slf4j
public class BookingOrchestrationService {

    private final CompositeOrderRepository repository;
    private final BookingClientRegistry clientRegistry;
    private final TransactionTemplate transactionTemplate;

    public CompositeOrder orchestrate(UUID orderId) {
        inTransaction(() -> {
            findOrder(orderId).startProcessing();
            return true;
        });

        while (true) {
            Optional<BookingStepCommand> nextStep = inTransaction(
                    () -> startNextStep(orderId)
            );

            if (nextStep.isEmpty()) {
                return inTransaction(() -> completeOrder(orderId));
            }

            bookStep(orderId, nextStep.orElseThrow());
        }
    }

    public CompositeOrder cancel(UUID orderId) {
        return cancelConfirmedSteps(orderId, true);
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

    private void bookStep(UUID orderId, BookingStepCommand step) {
        try {
            BookingClient client = clientRegistry.getClient(step.type());
            BookingResponse response = client.book(new BookingRequest(
                    step.resourceId(),
                    step.bookingReference()
            ));

            validateBookingReference(step, response);
            inTransaction(() -> {
                confirmStep(orderId, step.bookingReference(), response.id());
                return true;
            });
        } catch (ExternalBookingException exception) {
            log.warn(
                    "External booking failed: orderId={}, bookingStepId={}, "
                            + "bookingType={}, httpStatus={}, message={}",
                    orderId,
                    step.bookingReference(),
                    step.type(),
                    exception.getHttpStatus().value(),
                    exception.getMessage()
            );
            handleBookingFailure(orderId, exception);
            throw exception;
        } catch (RuntimeException exception) {
            handleBookingFailure(orderId, exception);
            throw exception;
        }
    }

    private void handleBookingFailure(UUID orderId, RuntimeException bookingFailure) {
        failCurrentStep(orderId);
        try {
            cancelConfirmedSteps(orderId, false);
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

    private CompositeOrder cancelConfirmedSteps(
            UUID orderId,
            boolean cancelOrderWithoutConfirmedSteps
    ) {
        List<CancellationCommand> commands = inTransaction(
                () -> startCancellation(
                        orderId,
                        cancelOrderWithoutConfirmedSteps
                )
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
            validateCancellationResponse(command, response);
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

    private void validateCancellationResponse(
            CancellationCommand command,
            CancelBookingResponse response
    ) {
        if (response == null) {
            throw new IllegalStateException("External cancellation returned no response");
        }
        if (!command.externalBookingId().equals(response.id())) {
            throw new IllegalStateException(
                    "Cancellation response id does not match external booking "
                            + command.externalBookingId()
            );
        }
        if (!"CANCELLED".equals(response.status())) {
            throw new IllegalStateException(
                    "Unexpected external cancellation status: " + response.status()
            );
        }
    }

    private void validateBookingReference(
            BookingStepCommand step,
            BookingResponse response
    ) {
        if (!step.bookingReference().equals(response.bookingReference())) {
            throw new IllegalStateException(
                    "Booking response reference does not match step "
                            + step.bookingReference()
            );
        }
    }

    private void confirmStep(
            UUID orderId,
            UUID bookingReference,
            UUID externalBookingId
    ) {
        findBookingStep(orderId, bookingReference)
                .confirm(externalBookingId.toString());
    }

    private BookingStep findBookingStep(UUID orderId, UUID stepId) {
        return findOrder(orderId).getBookingSteps().stream()
                .filter(candidate -> Objects.equals(candidate.getId(), stepId))
                .findFirst()
                .orElseThrow(() -> new InvalidStateTransitionException(
                        "Booking step not found: " + stepId
                ));
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

    private record CancellationCommand(
            UUID stepId,
            BookingType type,
            UUID externalBookingId
    ) {
    }
}
