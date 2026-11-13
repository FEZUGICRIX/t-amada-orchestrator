package com.tamada.travel.orchestrator.service;

import com.tamada.travel.orchestrator.dto.*;
import com.tamada.travel.orchestrator.exception.InvalidBookingDataException;
import com.tamada.travel.orchestrator.exception.OrderNotFoundException;
import com.tamada.travel.orchestrator.model.BookingType;
import com.tamada.travel.orchestrator.model.CompositeOrder;
import com.tamada.travel.orchestrator.repository.CompositeOrderRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
public class CompositeOrderService {

    private final CompositeOrderRepository repository;
    private final BookingOrchestrationService orchestrationService;
    private final OutboxService outboxService;

    @Autowired
    public CompositeOrderService(
            CompositeOrderRepository repository,
            BookingOrchestrationService orchestrationService,
            OutboxService outboxService
    ) {
        this.repository = repository;
        this.orchestrationService = orchestrationService;
        this.outboxService = outboxService;
    }

    public CompositeOrderService(
            CompositeOrderRepository repository,
            BookingOrchestrationService orchestrationService
    ) {
        this(repository, orchestrationService, null);
    }

    @Transactional
    public OrderResponse createOrder(CreateOrderRequest request) {
        validateBookings(request.bookings());

        CompositeOrder order = CompositeOrder.create();

        for (var booking : request.bookings()) {
            order.addBookingStep(booking.type(), booking.resourceId(), booking.price());
        }

        if (request.passengers() != null) {
            for (var p : request.passengers()) {
                order.addPassenger(
                        p.type(),
                        p.firstName(),
                        p.lastName(),
                        p.birthDate(),
                        p.citizenship(),
                        p.documentType(),
                        p.documentNumber()
                );
            }
        }

        if (request.customerContact() != null) {
            order.setCustomerContact(
                    request.customerContact().phone(),
                    request.customerContact().email()
            );
        }

        CompositeOrder savedOrder = repository.save(order);

        if (outboxService != null) {
            outboxService.saveOrderCreatedEvent(savedOrder);
        }

        return toResponse(savedOrder);
    }

    @Transactional(readOnly = true)
    public OrderResponse getOrder(UUID id) {
        CompositeOrder order = repository.findById(id)
                .orElseThrow(() -> new OrderNotFoundException(id));

        return toResponse(order);
    }

    public OrderResponse startOrder(UUID id) {
        CompositeOrder order = orchestrationService.orchestrate(id);
        return toResponse(order);
    }

    public OrderResponse cancelOrder(UUID id) {
        CompositeOrder order = orchestrationService.cancel(id);
        return toResponse(order);
    }

    private void validateBookings(List<CreateBookingStepRequest> bookings) {
        if (bookings == null || bookings.isEmpty()) {
            throw new InvalidBookingDataException("Bookings must not be empty");
        }

        long distinctResources = bookings.stream()
                .map(CreateBookingStepRequest::resourceId)
                .distinct()
                .count();
        if (distinctResources != bookings.size()) {
            throw new InvalidBookingDataException("Booking resource IDs must not contain duplicates");
        }

        long flightCount = bookings.stream().filter(b -> b.type() == BookingType.FLIGHT).count();
        long hotelCount = bookings.stream().filter(b -> b.type() == BookingType.HOTEL).count();
        long insuranceCount = bookings.stream().filter(b -> b.type() == BookingType.INSURANCE).count();
        long transferCount = bookings.stream().filter(b -> b.type() == BookingType.TRANSFER).count();

        if (flightCount > 2) {
            throw new InvalidBookingDataException("Booking types must not contain duplicates");
        }
        if (hotelCount > 1 || insuranceCount > 1 || transferCount > 1) {
            throw new InvalidBookingDataException("Booking types must not contain duplicates");
        }
    }

    private OrderResponse toResponse(CompositeOrder order) {
        var steps = order.getBookingSteps()
                .stream()
                .map(step -> new BookingStepResponse(
                        step.getId(),
                        step.getType(),
                        step.getResourceId(),
                        step.getStatus(),
                        step.getExternalId(),
                        step.getPrice()))
                .toList();

        var passengers = order.getPassengers() != null
                ? order.getPassengers().stream()
                .map(p -> new PassengerResponse(
                        p.getId(),
                        p.getType(),
                        p.getFirstName(),
                        p.getLastName(),
                        p.getBirthDate(),
                        p.getCitizenship(),
                        p.getDocumentType(),
                        p.getDocumentNumber()))
                .toList()
                : List.<PassengerResponse>of();

        CustomerContactResponse contactResponse = null;
        if (order.getCustomerContact() != null) {
            contactResponse = new CustomerContactResponse(
                    order.getCustomerContact().getId(),
                    order.getCustomerContact().getPhone(),
                    order.getCustomerContact().getEmail()
            );
        }

        return new OrderResponse(
                order.getId(),
                order.getStatus(),
                order.getCreatedAt(),
                order.getUpdatedAt(),
                order.getBasePrice(),
                order.getServiceFee(),
                order.getTotalPrice(),
                steps,
                passengers,
                contactResponse);
    }
}
