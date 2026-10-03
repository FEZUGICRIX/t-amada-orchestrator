package com.tamada.travel.orchestrator.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.UuidGenerator;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.hibernate.annotations.UuidGenerator.Style.VERSION_7;

@Entity
@Table(name = "composite_orders")
@EntityListeners(AuditingEntityListener.class)
@NoArgsConstructor
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

    @OneToMany(
            mappedBy = "compositeOrder",
            cascade = CascadeType.ALL,
            orphanRemoval = true
    )
    private List<BookingStep> bookingSteps = new ArrayList<>();

    public CompositeOrder(OrderStatus status) {
        this.status = status;
    }

    public void addBookingStep(BookingType type) {
        BookingStep bookingStep = new BookingStep(this, type);
        bookingSteps.add(bookingStep);
    }
}