package com.tamada.travel.orchestrator.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.tamada.travel.orchestrator.dto.outbox.OrderCreatedOutboxPayload;
import com.tamada.travel.orchestrator.model.CompositeOrder;
import com.tamada.travel.orchestrator.model.OutboxMessage;
import com.tamada.travel.orchestrator.model.Passenger;
import com.tamada.travel.orchestrator.model.PassengerType;
import com.tamada.travel.orchestrator.repository.OutboxRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

@Service
@Slf4j
public class OutboxService {

    private final OutboxRepository outboxRepository;
    private final ObjectMapper objectMapper;

    @Autowired
    public OutboxService(
            OutboxRepository outboxRepository,
            @Autowired(required = false) ObjectMapper objectMapper
    ) {
        this.outboxRepository = outboxRepository;
        this.objectMapper = objectMapper != null
                ? objectMapper
                : new ObjectMapper().registerModule(new JavaTimeModule());
    }

    @Transactional
    public OutboxMessage saveOrderCreatedEvent(CompositeOrder order) {
        OrderCreatedOutboxPayload payload = buildPayload(order);
        String jsonPayload;
        try {
            jsonPayload = objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize outbox payload for order {}", order.getId(), e);
            throw new IllegalStateException("Could not serialize outbox payload", e);
        }

        OutboxMessage message = new OutboxMessage(
                "COMPOSITE_ORDER",
                order.getId().toString(),
                "ORDER_CREATED",
                jsonPayload
        );

        OutboxMessage saved = outboxRepository.save(message);
        log.info("Saved outbox message id={} for order={}", saved.getId(), order.getId());
        return saved;
    }

    private OrderCreatedOutboxPayload buildPayload(CompositeOrder order) {
        List<Passenger> passengers = order.getPassengers();

        int adultsCount = (int) passengers.stream()
                .filter(p -> p.getType() == PassengerType.ADULT)
                .count();
        int childrenCount = (int) passengers.stream()
                .filter(p -> p.getType() == PassengerType.CHILD)
                .count();
        int infantsCount = (int) passengers.stream()
                .filter(p -> p.getType() == PassengerType.INFANT)
                .count();

        var passengerPayloads = passengers.stream()
                .map(p -> new OrderCreatedOutboxPayload.PassengerPayload(
                        p.getId(),
                        p.getType(),
                        p.getFirstName(),
                        p.getLastName(),
                        p.getBirthDate(),
                        p.getCitizenship(),
                        p.getDocumentType(),
                        p.getDocumentNumber()))
                .toList();

        var groupComposition = new OrderCreatedOutboxPayload.GroupCompositionPayload(
                passengers.size(),
                adultsCount,
                childrenCount,
                infantsCount,
                passengerPayloads
        );

        OrderCreatedOutboxPayload.CustomerContactPayload contactPayload = null;
        if (order.getCustomerContact() != null) {
            contactPayload = new OrderCreatedOutboxPayload.CustomerContactPayload(
                    order.getCustomerContact().getPhone(),
                    order.getCustomerContact().getEmail()
            );
        }

        var selectedOptions = order.getBookingSteps().stream()
                .map(step -> new OrderCreatedOutboxPayload.SelectedOptionPayload(
                        step.getId(),
                        step.getType(),
                        step.getResourceId(),
                        step.getStatus(),
                        step.getPrice()))
                .toList();

        return new OrderCreatedOutboxPayload(
                order.getId(),
                order.getStatus(),
                order.getBasePrice(),
                order.getServiceFee(),
                order.getTotalPrice(),
                contactPayload,
                groupComposition,
                selectedOptions,
                order.getCreatedAt() != null ? order.getCreatedAt() : Instant.now()
        );
    }
}
