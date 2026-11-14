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

    @CreatedDate
    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(nullable = false)
    private Instant updatedAt;

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
    }
}
