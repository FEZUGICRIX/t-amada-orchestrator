package com.tamada.travel.orchestrator.model;

import com.tamada.travel.orchestrator.exception.InvalidStateTransitionException;
import jakarta.persistence.*;
import lombok.Getter;
import org.hibernate.annotations.UuidGenerator;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.hibernate.annotations.UuidGenerator.Style.VERSION_7;

@Entity
@Table(name = "composite_orders")
@EntityListeners(AuditingEntityListener.class)
@Getter
public class CompositeOrder {

    @Id
    @UuidGenerator(style = VERSION_7)
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private OrderStatus status;

    @CreatedDate
    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(nullable = false)
    private Instant updatedAt;

    @OneToMany(mappedBy = "compositeOrder", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<BookingStep> bookingSteps = new ArrayList<>();

    protected CompositeOrder() {
    }

    public static CompositeOrder create() {
        CompositeOrder order = new CompositeOrder();
        order.status = OrderStatus.NEW;
        return order;
    }

    public void addBookingStep(BookingType type) {
        BookingStep bookingStep = new BookingStep(this, type);
        bookingSteps.add(bookingStep);
    }

    public void startProcessing() {
        if (status != OrderStatus.NEW) {
            throw new InvalidStateTransitionException(
                    "Cannot start order processing from status " + status);
        }

        this.status = OrderStatus.PROCESSING;
    }

    public void complete() {
        if (status != OrderStatus.PROCESSING) {
            throw new InvalidStateTransitionException(
                    "Cannot complete order from status " + status);
        }

        boolean allStepsConfirmed = bookingSteps.stream()
                .allMatch(step -> step.getStatus() == BookingStatus.CONFIRMED);

        if (!allStepsConfirmed) {
            throw new InvalidStateTransitionException(
                    "Cannot complete order while booking steps are not confirmed");
        }

        this.status = OrderStatus.COMPLETED;
    }

    public void fail() {
        if (status != OrderStatus.PROCESSING) {
            throw new InvalidStateTransitionException(
                    "Cannot fail order from status " + status);
        }

        this.status = OrderStatus.FAILED;
    }

    public void startCancelling() {
        if (status != OrderStatus.FAILED) {
            throw new InvalidStateTransitionException(
                    "Cannot start order cancellation from status " + status);
        }

        this.status = OrderStatus.CANCELLING;
    }

    public void cancel() {
        if (status != OrderStatus.CANCELLING) {
            throw new InvalidStateTransitionException(
                    "Cannot cancel order from status " + status);
        }

        this.status = OrderStatus.CANCELLED;
    }

    public Optional<BookingStep> startNextBookingStep() {
        boolean hasProcessingStep = bookingSteps.stream()
                .anyMatch(step -> step.getStatus() == BookingStatus.PROCESSING);

        if (hasProcessingStep) {
            return Optional.empty();
        }

        return bookingSteps.stream()
                .filter(step -> step.getStatus() == BookingStatus.PENDING)
                .findFirst()
                .map(step -> {
                    step.startProcessing();
                    return step;
                });
    }
}
