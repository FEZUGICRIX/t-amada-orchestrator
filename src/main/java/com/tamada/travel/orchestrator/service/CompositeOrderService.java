package com.tamada.travel.orchestrator.service;

import com.tamada.travel.orchestrator.dto.CreateOrderRequest;
import com.tamada.travel.orchestrator.dto.HotelRoomRequest;
import com.tamada.travel.orchestrator.dto.OrderOperationHistoryResponse;
import com.tamada.travel.orchestrator.dto.OrderResponse;
import com.tamada.travel.orchestrator.exception.OrderNotFoundException;
import com.tamada.travel.orchestrator.model.BookingType;
import com.tamada.travel.orchestrator.model.CompositeOrder;
import com.tamada.travel.orchestrator.model.OrderStatus;
import com.tamada.travel.orchestrator.repository.CompositeOrderRepository;
import com.tamada.travel.orchestrator.repository.OrderOperationHistoryRepository;
import com.tamada.travel.orchestrator.service.mapper.OrderMapper;
import com.tamada.travel.orchestrator.service.sse.OrderSseService;
import com.tamada.travel.orchestrator.service.validation.OrderValidator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Сервис управления составными заказами путешествий.
 * Обеспечивает создание заказа, сохранение событий в Outbox, запуск оркестрации,
 * ручную отмену, предоставление истории операций и SSE-подписку.
 */
@Service
public class CompositeOrderService {

    private final CompositeOrderRepository repository;
    private final BookingOrchestrationService orchestrationService;
    private final OutboxService outboxService;
    private final OrderValidator validator;
    private final OrderMapper orderMapper;
    private final OrderOperationHistoryRepository historyRepository;
    private final OrderSseService sseService;

    @Autowired
    public CompositeOrderService(
            CompositeOrderRepository repository,
            BookingOrchestrationService orchestrationService,
            @Autowired(required = false) OutboxService outboxService,
            OrderValidator validator,
            OrderMapper orderMapper,
            @Autowired(required = false) OrderOperationHistoryRepository historyRepository,
            @Autowired(required = false) OrderSseService sseService
    ) {
        this.repository = Objects.requireNonNull(repository, "repository must not be null");
        this.orchestrationService = Objects.requireNonNull(orchestrationService, "orchestrationService must not be null");
        this.outboxService = outboxService;
        this.validator = validator != null ? validator : new OrderValidator();
        this.orderMapper = orderMapper != null ? orderMapper : new OrderMapper();
        this.historyRepository = historyRepository;
        this.sseService = sseService;
    }

    public CompositeOrderService(
            CompositeOrderRepository repository,
            BookingOrchestrationService orchestrationService,
            OutboxService outboxService,
            OrderValidator validator,
            OrderMapper orderMapper
    ) {
        this(repository, orchestrationService, outboxService, validator, orderMapper, null, null);
    }

    public CompositeOrderService(
            CompositeOrderRepository repository,
            BookingOrchestrationService orchestrationService,
            OutboxService outboxService
    ) {
        this(repository, orchestrationService, outboxService, new OrderValidator(), new OrderMapper(), null, null);
    }

    public CompositeOrderService(
            CompositeOrderRepository repository,
            BookingOrchestrationService orchestrationService
    ) {
        this(repository, orchestrationService, null, new OrderValidator(), new OrderMapper(), null, null);
    }

    @Transactional
    public OrderResponse createOrder(CreateOrderRequest request) {
        validator.validateBookings(request.bookings());

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

        order.recordOperation(
                null,
                null,
                "ORDER_CREATE",
                "NEW",
                null,
                null,
                null,
                "Заказ успешно создан со статусом NEW.",
                null,
                null
        );

        CompositeOrder savedOrder = repository.save(order);

        if (outboxService != null) {
            outboxService.saveOrderCreatedEvent(savedOrder);
        }

        return orderMapper.toResponse(savedOrder);
    }

    @Transactional(readOnly = true)
    public OrderResponse getOrder(UUID id) {
        CompositeOrder order = repository.findById(id)
                .orElseThrow(() -> new OrderNotFoundException(id));

        return orderMapper.toResponse(order);
    }

    @Transactional(readOnly = true)
    public List<OrderOperationHistoryResponse> getOrderHistory(UUID id) {
        CompositeOrder order = repository.findById(id)
                .orElseThrow(() -> new OrderNotFoundException(id));

        if (historyRepository != null) {
            return historyRepository.findByCompositeOrderIdOrderByCreatedAtAsc(id).stream()
                    .map(orderMapper::toOperationHistoryResponse)
                    .toList();
        }

        return order.getOperationHistory().stream()
                .map(orderMapper::toOperationHistoryResponse)
                .toList();
    }

    public SseEmitter subscribeToOrderEvents(UUID id, boolean start) {
        CompositeOrder order = repository.findById(id)
                .orElseThrow(() -> new OrderNotFoundException(id));

        if (sseService == null) {
            throw new IllegalStateException("SSE service is not available");
        }

        SseEmitter emitter = sseService.subscribe(id, order);

        if (start && order.getStatus() == OrderStatus.NEW) {
            CompletableFuture.runAsync(() -> {
                try {
                    startOrder(id);
                } catch (Exception ignored) {
                }
            });
        }

        return emitter;
    }

    public OrderResponse startOrder(UUID id) {
        CompositeOrder order = orchestrationService.orchestrate(id);
        if (outboxService != null) {
            if (order.getStatus() == OrderStatus.COMPLETED) {
                outboxService.saveOrderCompletedEvent(order);
            } else if (order.getStatus() == OrderStatus.CANCELLED) {
                outboxService.saveOrderCancelledEvent(order);
            }
        }
        return orderMapper.toResponse(order);
    }

    public OrderResponse cancelOrder(UUID id) {
        CompositeOrder order = orchestrationService.cancel(id);
        if (outboxService != null && order.getStatus() == OrderStatus.CANCELLED) {
            outboxService.saveOrderCancelledEvent(order);
        }
        return orderMapper.toResponse(order);
    }
}
