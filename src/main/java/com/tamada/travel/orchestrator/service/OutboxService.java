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

    public static final String AGGREGATE_TYPE_COMPOSITE_ORDER = "COMPOSITE_ORDER";
    public static final String EVENT_ORDER_CREATED = "ORDER_CREATED";
    public static final String EVENT_ORDER_PROCESSING = "ORDER_PROCESSING";
    public static final String EVENT_ORDER_COMPLETED = "ORDER_COMPLETED";
    public static final String EVENT_ORDER_CANCELLED = "ORDER_CANCELLED";
    public static final String EVENT_ORDER_FAILED = "ORDER_FAILED";

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
        return saveOrderLifecycleEvent(order, EVENT_ORDER_CREATED);
    }

    @Transactional
    public OutboxMessage saveOrderProcessingEvent(CompositeOrder order) {
        return saveOrderLifecycleEvent(order, EVENT_ORDER_PROCESSING);
    }

    @Transactional
    public OutboxMessage saveOrderCompletedEvent(CompositeOrder order) {
        return saveOrderLifecycleEvent(order, EVENT_ORDER_COMPLETED);
    }

    @Transactional
    public OutboxMessage saveOrderCancelledEvent(CompositeOrder order) {
        return saveOrderLifecycleEvent(order, EVENT_ORDER_CANCELLED);
    }

    @Transactional
    public OutboxMessage saveOrderFailedEvent(CompositeOrder order) {
        return saveOrderLifecycleEvent(order, EVENT_ORDER_FAILED);
    }

    @Transactional
    public OutboxMessage saveOrderLifecycleEvent(CompositeOrder order, String eventType) {
        OrderCreatedOutboxPayload payload = buildPayload(order);
        String jsonPayload;
        try {
            jsonPayload = objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize outbox payload for order {}, event {}", order.getId(), eventType, e);
            throw new IllegalStateException("Could not serialize outbox payload", e);
        }

        OutboxMessage message = new OutboxMessage(
                AGGREGATE_TYPE_COMPOSITE_ORDER,
                order.getId().toString(),
                eventType,
                jsonPayload
        );

        OutboxMessage saved = outboxRepository.save(message);
        log.info("Saved outbox message id={} event={} for order={}", saved.getId(), eventType, order.getId());
        return saved;
    }

    private OrderCreatedOutboxPayload buildPayload(CompositeOrder order) {
        List<Passenger> passengers = order.getPassengers() != null ? order.getPassengers() : List.of();

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

        var steps = order.getBookingSteps() != null ? order.getBookingSteps() : List.<com.tamada.travel.orchestrator.model.BookingStep>of();
        var selectedOptions = steps.stream()
                .map(step -> {
                    Integer roomsCount = null;
                    List<OrderCreatedOutboxPayload.HotelRoomPayload> roomPayloads = null;
                    if (step.getType() == com.tamada.travel.orchestrator.model.BookingType.HOTEL && step.getRooms() != null && !step.getRooms().isEmpty()) {
                        roomsCount = step.getRooms().size();
                        roomPayloads = step.getRooms().stream()
                                .map(r -> new OrderCreatedOutboxPayload.HotelRoomPayload(
                                        r.getId(),
                                        r.getCategory(),
                                        r.getTariff(),
                                        r.getPrice(),
                                        r.getStayConditions()))
                                .toList();
                    }
                    return new OrderCreatedOutboxPayload.SelectedOptionPayload(
                            step.getId(),
                            step.getType(),
                            step.getResourceId(),
                            step.getStatus(),
                            step.getPrice(),
                            roomsCount,
                            roomPayloads);
                })
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
