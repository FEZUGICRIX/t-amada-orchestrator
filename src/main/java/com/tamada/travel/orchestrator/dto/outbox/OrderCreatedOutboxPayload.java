package com.tamada.travel.orchestrator.dto.outbox;

import com.tamada.travel.orchestrator.model.BookingStatus;
import com.tamada.travel.orchestrator.model.BookingType;
import com.tamada.travel.orchestrator.model.OrderStatus;
import com.tamada.travel.orchestrator.model.PassengerType;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record OrderCreatedOutboxPayload(
        UUID orderId,
        OrderStatus status,
        BigDecimal basePrice,
        BigDecimal serviceFee,
        BigDecimal totalPrice,
        CustomerContactPayload customerContact,
        GroupCompositionPayload groupComposition,
        List<SelectedOptionPayload> selectedOptions,
        Instant createdAt
) {
    public OrderCreatedOutboxPayload(
            UUID orderId,
            OrderStatus status,
            CustomerContactPayload customerContact,
            GroupCompositionPayload groupComposition,
            List<SelectedOptionPayload> selectedOptions,
            Instant createdAt
    ) {
        this(
                orderId,
                status,
                BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP),
                BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP),
                BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP),
                customerContact,
                groupComposition,
                selectedOptions,
                createdAt
        );
    }

    public record CustomerContactPayload(
            String phone,
            String email
    ) {
    }

    public record GroupCompositionPayload(
            int totalPassengers,
            int adultsCount,
            int childrenCount,
            int infantsCount,
            List<PassengerPayload> passengers
    ) {
    }

    public record PassengerPayload(
            UUID id,
            PassengerType type,
            String firstName,
            String lastName,
            LocalDate birthDate,
            String citizenship,
            String documentType,
            String documentNumber
    ) {
    }

    public record SelectedOptionPayload(
            UUID stepId,
            BookingType type,
            UUID resourceId,
            BookingStatus status,
            BigDecimal price
    ) {
        public SelectedOptionPayload(
                UUID stepId,
                BookingType type,
                UUID resourceId,
                BookingStatus status
        ) {
            this(stepId, type, resourceId, status, BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP));
        }
    }
}
