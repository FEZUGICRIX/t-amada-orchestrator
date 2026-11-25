package com.tamada.travel.orchestrator.service.orchestration;

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
import com.tamada.travel.orchestrator.model.PassengerType;
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
 * Исполнитель отдельного шага бронирования (forward transaction).
 * Отвечает за вызов внешнего сервиса, валидацию ответа и фиксацию статуса шага в БД.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class StepExecutor {

    private final BookingClientRegistry clientRegistry;
    private final StepResponseValidator responseValidator;
    private final CompositeOrderRepository repository;
    private final TransactionTemplate transactionTemplate;

    public StepExecutionResult executeStep(
            UUID orderId,
            UUID bookingReference,
            BookingType type,
            UUID resourceId
    ) {
        try {
            BookingRequest request = buildBookingRequest(orderId, bookingReference, type, resourceId);
            BookingResponse response = clientRegistry.book(type, request);

            responseValidator.validateBookingResponse(bookingReference, response);

            inTransaction(() -> {
                findBookingStep(orderId, bookingReference).confirm(response.id().toString());
                return true;
            });

            return new StepExecutionResult.Success(response.id());
        } catch (ExternalBookingException exception) {
            log.warn(
                    "External booking failed: orderId={}, bookingStepId={}, "
                            + "bookingType={}, httpStatus={}, message={}",
                    orderId,
                    bookingReference,
                    type,
                    exception.getHttpStatus().value(),
                    exception.getMessage()
            );
            return new StepExecutionResult.Failure(exception);
        } catch (RuntimeException exception) {
            log.warn(
                    "Booking step execution failed: orderId={}, bookingStepId={}, "
                            + "bookingType={}, message={}",
                    orderId,
                    bookingReference,
                    type,
                    exception.getMessage()
            );
            return new StepExecutionResult.Failure(exception);
        }
    }

    private BookingRequest buildBookingRequest(UUID orderId, UUID bookingReference, BookingType type, UUID resourceId) {
        try {
            return inTransaction(() -> {
                CompositeOrder order = findOrder(orderId);
                BookingStep step = order.findBookingStep(bookingReference).orElse(null);
                java.math.BigDecimal price = (step != null && step.getPrice() != null && step.getPrice().compareTo(java.math.BigDecimal.ZERO) > 0)
                        ? step.getPrice()
                        : null;

                List<BookingRequest.RoomDetail> rooms = (step != null && step.getRooms() != null && !step.getRooms().isEmpty())
                        ? step.getRooms().stream()
                        .map(r -> new BookingRequest.RoomDetail(r.getId(), r.getCategory(), r.getTariff(), r.getPrice(), r.getStayConditions()))
                        .toList()
                        : null;

                boolean hasPassengers = order.getPassengers() != null && !order.getPassengers().isEmpty();
                Integer seatsCount = hasPassengers ? order.calculateSeatsCount() : null;
                Integer adultsCount = hasPassengers ? order.countPassengersByType(PassengerType.ADULT) : null;
                Integer childrenCount = hasPassengers ? order.countPassengersByType(PassengerType.CHILD) : null;
                Integer infantsCount = hasPassengers ? order.countPassengersByType(PassengerType.INFANT) : null;

                BookingRequest.CustomerContactDetail contact = (order.getCustomerContact() != null)
                        ? new BookingRequest.CustomerContactDetail(order.getCustomerContact().getPhone(), order.getCustomerContact().getEmail())
                        : null;

                return new BookingRequest(
                        resourceId,
                        bookingReference,
                        price,
                        "RUB",
                        seatsCount,
                        adultsCount,
                        childrenCount,
                        infantsCount,
                        rooms,
                        contact
                );
            });
        } catch (Exception ex) {
            return new BookingRequest(resourceId, bookingReference);
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
}
