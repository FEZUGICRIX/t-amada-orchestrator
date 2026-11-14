package com.tamada.travel.orchestrator.model;

import com.tamada.travel.orchestrator.exception.InvalidBookingDataException;
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
import java.util.UUID;

import static org.hibernate.annotations.UuidGenerator.Style.VERSION_7;

@Entity
@Table(name = "hotel_rooms")
@EntityListeners(AuditingEntityListener.class)
@NoArgsConstructor
@Getter
public class HotelRoom {

    @Id
    @UuidGenerator(style = VERSION_7)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "booking_step_id", nullable = false)
    private BookingStep bookingStep;

    @Column(nullable = false)
    private String category;

    @Column(nullable = false)
    private String tariff;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal price = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);

    @Column(name = "stay_conditions", length = 1000)
    private String stayConditions;

    @CreatedDate
    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(nullable = false)
    private Instant updatedAt;

    public HotelRoom(
            BookingStep bookingStep,
            String category,
            String tariff,
            BigDecimal price,
            String stayConditions
    ) {
        if (category == null || category.isBlank()) {
            throw new InvalidBookingDataException("Room category must not be empty");
        }
        if (tariff == null || tariff.isBlank()) {
            throw new InvalidBookingDataException("Room tariff must not be empty");
        }
        if (price != null && price.compareTo(BigDecimal.ZERO) < 0) {
            throw new InvalidBookingDataException("Room price must not be negative");
        }

        this.bookingStep = bookingStep;
        this.category = category.trim();
        this.tariff = tariff.trim();
        this.price = price != null
                ? price.setScale(2, RoundingMode.HALF_UP)
                : BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        this.stayConditions = stayConditions != null ? stayConditions.trim() : null;
    }
}
