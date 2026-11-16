package com.tamada.travel.orchestrator.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.UuidGenerator;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;
import java.util.UUID;

import static org.hibernate.annotations.UuidGenerator.Style.VERSION_7;

@Entity
@Table(name = "order_operation_history")
@EntityListeners(AuditingEntityListener.class)
@NoArgsConstructor
@Getter
public class OrderOperationHistory {

    @Id
    @UuidGenerator(style = VERSION_7)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "composite_order_id", nullable = false)
    private CompositeOrder compositeOrder;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "booking_step_id")
    private BookingStep bookingStep;

    @Enumerated(EnumType.STRING)
    @Column(name = "booking_type", length = 50)
    private BookingType bookingType;

    @Column(name = "operation_type", nullable = false, length = 50)
    private String operationType;

    @Column(nullable = false, length = 50)
    private String status;

    @Column(name = "http_status")
    private Integer httpStatus;

    @Column(name = "error_code", length = 100)
    private String errorCode;

    @Column(columnDefinition = "TEXT")
    private String message;

    @Column(name = "user_message", columnDefinition = "TEXT")
    private String userMessage;

    @Column(columnDefinition = "TEXT")
    private String details;

    @Column(name = "cancellation_reason", columnDefinition = "TEXT")
    private String cancellationReason;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    public OrderOperationHistory(
            CompositeOrder compositeOrder,
            BookingStep bookingStep,
            BookingType bookingType,
            String operationType,
            String status,
            Integer httpStatus,
            String errorCode,
            String message,
            String userMessage,
            String details,
            String cancellationReason
    ) {
        this.compositeOrder = compositeOrder;
        this.bookingStep = bookingStep;
        this.bookingType = bookingType;
        this.operationType = operationType;
        this.status = status;
        this.httpStatus = httpStatus;
        this.errorCode = errorCode;
        this.message = message;
        this.userMessage = userMessage;
        this.details = details;
        this.cancellationReason = cancellationReason;
        this.createdAt = Instant.now();
    }
}
