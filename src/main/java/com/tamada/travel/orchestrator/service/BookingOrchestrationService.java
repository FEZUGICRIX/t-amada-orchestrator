package com.tamada.travel.orchestrator.service;

import com.tamada.travel.orchestrator.client.BookingClient;
import com.tamada.travel.orchestrator.client.BookingClientRegistry;
import com.tamada.travel.orchestrator.client.dto.BookingRequest;
import com.tamada.travel.orchestrator.client.dto.BookingResponse;
import com.tamada.travel.orchestrator.exception.ExternalBookingException;
import com.tamada.travel.orchestrator.exception.InvalidStateTransitionException;
import com.tamada.travel.orchestrator.exception.OrderNotFoundException;
import com.tamada.travel.orchestrator.model.BookingStep;
import com.tamada.travel.orchestrator.model.BookingType;
import com.tamada.travel.orchestrator.model.CompositeOrder;
import com.tamada.travel.orchestrator.repository.CompositeOrderRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

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
            failCurrentStep(orderId);
            log.warn(
                    "External booking failed: orderId={}, bookingStepId={}, "
                            + "bookingType={}, httpStatus={}, message={}",
                    orderId,
                    step.bookingReference(),
                    step.type(),
                    exception.getHttpStatus().value(),
                    exception.getMessage()
            );
            throw exception;
        } catch (RuntimeException exception) {
            failCurrentStep(orderId);
            throw exception;
        }
    }

    private void failCurrentStep(UUID orderId) {
        inTransaction(() -> {
            findOrder(orderId).failCurrentBookingStep();
            return true;
        });
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
        CompositeOrder order = findOrder(orderId);
        BookingStep step = order.getBookingSteps().stream()
                .filter(candidate -> Objects.equals(
                        candidate.getId(),
                        bookingReference
                ))
                .findFirst()
                .orElseThrow(() -> new InvalidStateTransitionException(
                        "Booking step not found: " + bookingReference
                ));

        step.confirm(externalBookingId.toString());
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
