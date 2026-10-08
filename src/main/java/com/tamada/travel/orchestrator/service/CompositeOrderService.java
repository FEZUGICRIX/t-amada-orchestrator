package com.tamada.travel.orchestrator.service;

import com.tamada.travel.orchestrator.dto.BookingStepResponse;
import com.tamada.travel.orchestrator.dto.CreateOrderRequest;
import com.tamada.travel.orchestrator.dto.OrderResponse;
import com.tamada.travel.orchestrator.exception.InvalidBookingDataException;
import com.tamada.travel.orchestrator.exception.OrderNotFoundException;
import com.tamada.travel.orchestrator.model.CompositeOrder;
import com.tamada.travel.orchestrator.repository.CompositeOrderRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class CompositeOrderService {

    private final CompositeOrderRepository repository;

    @Transactional
    public OrderResponse createOrder(CreateOrderRequest request) {
        if (request.bookingTypes().stream().distinct().count()
                != request.bookingTypes().size()) {

            throw new InvalidBookingDataException(
                    "Booking types must not contain duplicates"
            );
        }

        CompositeOrder order = CompositeOrder.create();

        for (var type : request.bookingTypes()) {
            order.addBookingStep(type);
        }

        CompositeOrder savedOrder = repository.save(order);

        return toResponse(savedOrder);
    }

    @Transactional(readOnly = true)
    public OrderResponse getOrder(UUID id) {
        CompositeOrder order = repository.findById(id)
                .orElseThrow(() -> new OrderNotFoundException(id));

        return toResponse(order);
    }

    @Transactional
    public OrderResponse startOrder(UUID id) {
        CompositeOrder order = repository.findById(id)
                .orElseThrow(() -> new OrderNotFoundException(id));

        order.startProcessing();

        return toResponse(order);
    }

    private OrderResponse toResponse(CompositeOrder order) {
        var steps = order.getBookingSteps()
                .stream()
                .map(step -> new BookingStepResponse(
                        step.getId(),
                        step.getType(),
                        step.getStatus(),
                        step.getExternalId()
                ))
                .toList();

        return new OrderResponse(
                order.getId(),
                order.getStatus(),
                order.getCreatedAt(),
                order.getUpdatedAt(),
                steps
        );
    }
}