package com.tamada.travel.orchestrator.model;

import com.tamada.travel.orchestrator.exception.InvalidBookingDataException;
import com.tamada.travel.orchestrator.exception.InvalidStateTransitionException;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.UuidGenerator;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;
import java.util.UUID;

import static org.hibernate.annotations.UuidGenerator.Style.VERSION_7;

@Entity
@Table(name = "booking_steps")
@EntityListeners(AuditingEntityListener.class)
@NoArgsConstructor
@Getter
public class BookingStep {

    @Id
    @UuidGenerator(style = VERSION_7)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "composite_order_id", nullable = false)
    private CompositeOrder compositeOrder;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private BookingType type;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private BookingStatus status;

    private String externalId;

    @CreatedDate
    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(nullable = false)
    private Instant updatedAt;

    public BookingStep(CompositeOrder compositeOrder, BookingType type) {
        this.compositeOrder = compositeOrder;
        this.type = type;
        this.status = BookingStatus.PENDING;
    }

    public void startProcessing() {
        if (status != BookingStatus.PENDING) {
            throw new InvalidStateTransitionException(
                    "Cannot start booking step from status " + status
            );
        }

        this.status = BookingStatus.PROCESSING;
    }

    public void confirm(String externalId) {
        if (status != BookingStatus.PROCESSING) {
            throw new InvalidStateTransitionException(
                    "Cannot confirm booking step from status " + status
            );
        }

        if (externalId == null || externalId.isBlank()) {
            throw new InvalidBookingDataException(
                    "External booking id must not be empty"
            );
        }

        this.externalId = externalId;
        this.status = BookingStatus.CONFIRMED;
    }

    public void fail() {
        if (status != BookingStatus.PROCESSING) {
            throw new InvalidStateTransitionException(
                    "Cannot fail booking step from status " + status
            );
        }

        this.status = BookingStatus.FAILED;
    }

    public void startCancelling() {
        if (status != BookingStatus.CONFIRMED) {
            throw new InvalidStateTransitionException(
                    "Cannot start cancellation from status " + status
            );
        }

        this.status = BookingStatus.CANCELLING;
    }

    public void cancel() {
        if (status != BookingStatus.CANCELLING) {
            throw new InvalidStateTransitionException(
                    "Cannot cancel booking step from status " + status
            );
        }

        this.status = BookingStatus.CANCELLED;
    }}