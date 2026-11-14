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

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
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
            BigDecimal price = booking.price() != null ? booking.price() : BigDecimal.ZERO;
            if (booking.type() == BookingType.HOTEL && booking.rooms() != null && !booking.rooms().isEmpty()) {
                BigDecimal roomsTotal = booking.rooms().stream()
                        .map(HotelRoomRequest::price)
                        .filter(Objects::nonNull)
                        .reduce(BigDecimal.ZERO, BigDecimal::add);
                if (price.compareTo(BigDecimal.ZERO) == 0) {
                    price = roomsTotal;
                }
            }

            var step = order.addBookingStep(booking.type(), booking.resourceId(), price);
            if (booking.type() == BookingType.HOTEL && booking.rooms() != null && !booking.rooms().isEmpty()) {
                for (var roomReq : booking.rooms()) {
                    step.addRoom(
                            roomReq.category(),
                            roomReq.tariff(),
                            roomReq.price(),
                            roomReq.stayConditions()
                    );
                }
                step.recalculatePriceFromRooms();
                order.recalculatePricing();
            }
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

        for (var booking : bookings) {
            if (booking.type() != BookingType.HOTEL) {
                if (booking.rooms() != null && !booking.rooms().isEmpty()) {
                    throw new InvalidBookingDataException("Rooms can only be specified for HOTEL bookings");
                }
                if (booking.roomsCount() != null) {
                    throw new InvalidBookingDataException("Rooms count can only be specified for HOTEL bookings");
                }
            } else {
                if (booking.rooms() != null && !booking.rooms().isEmpty()) {
                    if (booking.roomsCount() != null && !booking.roomsCount().equals(booking.rooms().size())) {
                        throw new InvalidBookingDataException(
                                "Rooms count (" + booking.roomsCount() + ") does not match provided rooms size (" + booking.rooms().size() + ")"
                        );
                    }
                    BigDecimal roomsTotal = booking.rooms().stream()
                            .map(HotelRoomRequest::price)
                            .filter(Objects::nonNull)
                            .reduce(BigDecimal.ZERO, BigDecimal::add);

                    if (booking.price() != null && booking.price().compareTo(BigDecimal.ZERO) > 0) {
                        if (booking.price().compareTo(roomsTotal) != 0) {
                            throw new InvalidBookingDataException(
                                    "Step price (" + booking.price() + ") does not match total room price (" + roomsTotal + ")"
                            );
                        }
                    }
                } else if (booking.roomsCount() != null && booking.roomsCount() > 0) {
                    throw new InvalidBookingDataException("Rooms parameters must be provided when roomsCount is specified");
                }
            }
        }
    }

    private OrderResponse toResponse(CompositeOrder order) {
        var steps = order.getBookingSteps()
                .stream()
                .map(step -> {
                    Integer roomsCount = null;
                    List<HotelRoomResponse> roomResponses = null;
                    if (step.getType() == BookingType.HOTEL && !step.getRooms().isEmpty()) {
                        roomsCount = step.getRooms().size();
                        roomResponses = step.getRooms().stream()
                                .map(r -> new HotelRoomResponse(
                                        r.getId(),
                                        r.getCategory(),
                                        r.getTariff(),
                                        r.getPrice(),
                                        r.getStayConditions()
                                ))
                                .toList();
                    }
                    return new BookingStepResponse(
                            step.getId(),
                            step.getType(),
                            step.getResourceId(),
                            step.getStatus(),
                            step.getExternalId(),
                            step.getPrice(),
                            roomsCount,
                            roomResponses
                    );
                })
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
