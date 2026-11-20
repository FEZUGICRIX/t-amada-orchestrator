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

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
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

    @Version
    @Column(nullable = false)
    private Long version;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "composite_order_id", nullable = false)
    private CompositeOrder compositeOrder;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private BookingType type;

    @Column(name = "resource_id", nullable = false, updatable = false)
    private UUID resourceId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private BookingStatus status;

    private String externalId;

    @Column(name = "price", nullable = false, precision = 12, scale = 2)
    private BigDecimal price = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);

    @OneToMany(mappedBy = "bookingStep", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("createdAt ASC, id ASC")
    private List<HotelRoom> rooms = new ArrayList<>();

    @Column(name = "error_code")
    private String errorCode;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "user_message", columnDefinition = "TEXT")
    private String userMessage;

    @Column(name = "error_details", columnDefinition = "TEXT")
    private String errorDetails;

    @Column(name = "cancellation_reason", columnDefinition = "TEXT")
    private String cancellationReason;

    @CreatedDate
    @Column(nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @LastModifiedDate
    @Column(nullable = false)
    private Instant updatedAt = Instant.now();

    public BookingStep(
            CompositeOrder compositeOrder,
            BookingType type,
            UUID resourceId
    ) {
        this(compositeOrder, type, resourceId, BigDecimal.ZERO);
    }

    public BookingStep(
            CompositeOrder compositeOrder,
            BookingType type,
            UUID resourceId,
            BigDecimal price
    ) {
        if (price != null && price.compareTo(BigDecimal.ZERO) < 0) {
            throw new InvalidBookingDataException("Step price must not be negative");
        }
        this.compositeOrder = compositeOrder;
        this.type = type;
        this.resourceId = resourceId;
        this.price = price != null
                ? price.setScale(2, RoundingMode.HALF_UP)
                : BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        this.status = BookingStatus.PENDING;
        this.createdAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    public void addRoom(String category, String tariff, BigDecimal roomPrice, String stayConditions) {
        if (this.type != BookingType.HOTEL) {
            throw new InvalidBookingDataException("Rooms can only be added to HOTEL booking step");
        }
        HotelRoom room = new HotelRoom(this, category, tariff, roomPrice, stayConditions);
        this.rooms.add(room);
    }

    public void recalculatePriceFromRooms() {
        if (rooms != null && !rooms.isEmpty()) {
            this.price = rooms.stream()
                    .map(HotelRoom::getPrice)
                    .filter(Objects::nonNull)
                    .reduce(BigDecimal.ZERO, BigDecimal::add)
                    .setScale(2, RoundingMode.HALF_UP);
        }
    }

    public int getRoomsCount() {
        return rooms != null ? rooms.size() : 0;
    }

    public void startProcessing() {
        if (status != BookingStatus.PENDING) {
            throw new InvalidStateTransitionException(
                    "Cannot start booking step from status " + status
            );
        }

        this.status = BookingStatus.PROCESSING;
    }

    public void resetToPending() {
        if (status != BookingStatus.PROCESSING) {
            throw new InvalidStateTransitionException(
                    "Cannot reset booking step to PENDING from status " + status
            );
        }

        this.status = BookingStatus.PENDING;
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
        fail(null, null, null, null);
    }

    public void fail(String errorCode, String errorMessage, String userMessage, String errorDetails) {
        if (status != BookingStatus.PROCESSING) {
            throw new InvalidStateTransitionException(
                    "Cannot fail booking step from status " + status
            );
        }

        this.status = BookingStatus.FAILED;
        this.errorCode = errorCode;
        this.errorMessage = errorMessage;
        this.userMessage = userMessage;
        this.errorDetails = errorDetails;
    }

    public void startCancelling() {
        startCancelling(null);
    }

    public void startCancelling(String reason) {
        if (status != BookingStatus.CONFIRMED && status != BookingStatus.CANCELLING) {
            throw new InvalidStateTransitionException(
                    "Cannot start cancellation from status " + status
            );
        }

        this.status = BookingStatus.CANCELLING;
        if (reason != null && !reason.isBlank()) {
            this.cancellationReason = reason;
        }
    }

    public void cancel() {
        cancel(null, null);
    }

    public void cancel(String reason, String userMessage) {
        if (status != BookingStatus.CANCELLING) {
            throw new InvalidStateTransitionException(
                    "Cannot cancel booking step from status " + status
            );
        }

        this.status = BookingStatus.CANCELLED;
        if (reason != null && !reason.isBlank()) {
            this.cancellationReason = reason;
        }
        if (userMessage != null && !userMessage.isBlank()) {
            this.userMessage = userMessage;
        }
    }

    public void markSkipped(String reason, String userMessage) {
        if (reason != null && !reason.isBlank()) {
            this.cancellationReason = reason;
        }
        if (userMessage != null && !userMessage.isBlank()) {
            this.userMessage = userMessage;
        }
    }
}
