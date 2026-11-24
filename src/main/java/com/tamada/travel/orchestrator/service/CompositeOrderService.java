package com.tamada.travel.orchestrator.service;

import com.tamada.travel.orchestrator.dto.CreateBookingStepRequest;
import com.tamada.travel.orchestrator.dto.CreateOrderRequest;
import com.tamada.travel.orchestrator.dto.HotelRoomRequest;
import com.tamada.travel.orchestrator.dto.OrderOperationHistoryResponse;
import com.tamada.travel.orchestrator.dto.OrderResponse;
import com.tamada.travel.orchestrator.dto.ReplaceHotelRequest;
import com.tamada.travel.orchestrator.exception.InvalidBookingDataException;
import com.tamada.travel.orchestrator.exception.InvalidStateTransitionException;
import com.tamada.travel.orchestrator.exception.OrderNotFoundException;
import com.tamada.travel.orchestrator.model.BookingStatus;
import com.tamada.travel.orchestrator.model.BookingStep;
import com.tamada.travel.orchestrator.model.BookingType;
import com.tamada.travel.orchestrator.model.CompositeOrder;
import com.tamada.travel.orchestrator.model.OrderStatus;
import com.tamada.travel.orchestrator.repository.CompositeOrderRepository;
import com.tamada.travel.orchestrator.repository.OrderOperationHistoryRepository;
import com.tamada.travel.orchestrator.service.mapper.OrderMapper;
import com.tamada.travel.orchestrator.service.orchestration.SagaCompensator;
import com.tamada.travel.orchestrator.service.sse.OrderSseService;
import com.tamada.travel.orchestrator.service.validation.OrderValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * Сервис управления составными заказами путешествий.
 * Обеспечивает создание заказа, сохранение событий в Outbox, запуск оркестрации,
 * ручную отмену, предоставление истории операций и SSE-подписку.
 */
@Service
public class CompositeOrderService {

    private static final Logger log = LoggerFactory.getLogger(CompositeOrderService.class);

    private final CompositeOrderRepository repository;
    private final BookingOrchestrationService orchestrationService;
    private final OutboxService outboxService;
    private final OrderValidator validator;
    private final OrderMapper orderMapper;
    private final OrderOperationHistoryRepository historyRepository;
    private final OrderSseService sseService;
    private final Executor sagaTaskExecutor;
    private final SagaCompensator compensator;
    private org.springframework.context.ApplicationEventPublisher eventPublisher;

    @Autowired
    public CompositeOrderService(
            CompositeOrderRepository repository,
            BookingOrchestrationService orchestrationService,
            @Autowired(required = false) OutboxService outboxService,
            OrderValidator validator,
            OrderMapper orderMapper,
            @Autowired(required = false) OrderOperationHistoryRepository historyRepository,
            @Autowired(required = false) OrderSseService sseService,
            @Autowired(required = false) @Qualifier("sagaTaskExecutor") Executor sagaTaskExecutor,
            @Autowired(required = false) SagaCompensator compensator,
            @Autowired(required = false) org.springframework.context.ApplicationEventPublisher eventPublisher
    ) {
        this.repository = Objects.requireNonNull(repository, "repository must not be null");
        this.orchestrationService = Objects.requireNonNull(orchestrationService, "orchestrationService must not be null");
        this.outboxService = outboxService;
        this.validator = validator != null ? validator : new OrderValidator();
        this.orderMapper = orderMapper != null ? orderMapper : new OrderMapper();
        this.historyRepository = historyRepository;
        this.sseService = sseService;
        this.sagaTaskExecutor = sagaTaskExecutor != null ? sagaTaskExecutor : CompletableFuture.delayedExecutor(0, java.util.concurrent.TimeUnit.SECONDS);
        this.compensator = compensator;
        this.eventPublisher = eventPublisher;
    }

    public CompositeOrderService(
            CompositeOrderRepository repository,
            BookingOrchestrationService orchestrationService,
            @Autowired(required = false) OutboxService outboxService,
            OrderValidator validator,
            OrderMapper orderMapper,
            @Autowired(required = false) OrderOperationHistoryRepository historyRepository,
            @Autowired(required = false) OrderSseService sseService,
            @Autowired(required = false) @Qualifier("sagaTaskExecutor") Executor sagaTaskExecutor,
            @Autowired(required = false) SagaCompensator compensator
    ) {
        this(repository, orchestrationService, outboxService, validator, orderMapper, historyRepository, sseService, sagaTaskExecutor, compensator, null);
    }

    public CompositeOrderService(
            CompositeOrderRepository repository,
            BookingOrchestrationService orchestrationService,
            OutboxService outboxService,
            OrderValidator validator,
            OrderMapper orderMapper,
            OrderOperationHistoryRepository historyRepository,
            OrderSseService sseService
    ) {
        this(repository, orchestrationService, outboxService, validator, orderMapper, historyRepository, sseService, null, null);
    }

    public CompositeOrderService(
            CompositeOrderRepository repository,
            BookingOrchestrationService orchestrationService
    ) {
        this(repository, orchestrationService, null, new OrderValidator(), new OrderMapper(), null, null, null, null);
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

        if (eventPublisher != null) {
            eventPublisher.publishEvent(new com.tamada.travel.orchestrator.event.OrderCreatedEvent(savedOrder.getId(), savedOrder));
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
                } catch (Exception e) {
                    log.error("Failed to automatically start order {} from SSE subscription: {}", id, e.getMessage(), e);
                }
            }, sagaTaskExecutor);
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
            } else if (order.getStatus() == OrderStatus.FAILED) {
                outboxService.saveOrderFailedEvent(order);
            }
        }
        return orderMapper.toResponse(order);
    }

    @Transactional
    public OrderResponse startOrderAsync(UUID id) {
        CompositeOrder order = repository.findById(id)
                .orElseThrow(() -> new OrderNotFoundException(id));

        order.startProcessing();
        order.clearPendingStepsSkipInfo();
        order.recordOperation(
                null,
                null,
                "ORDER_PROCESSING",
                "PROCESSING",
                null,
                null,
                null,
                "Начало процесса бронирования услуг заказа (асинхронно).",
                null,
                null
        );

        CompositeOrder savedOrder = repository.save(order);

        if (sseService != null) {
            sseService.publishOrderStatus(id, OrderStatus.PROCESSING);
        }

        if (eventPublisher != null) {
            eventPublisher.publishEvent(new com.tamada.travel.orchestrator.event.OrderStatusChangedEvent(id, OrderStatus.PROCESSING));
        }

        Runnable asyncExecution = () -> {
            try {
                CompositeOrder finished = orchestrationService.recover(id);
                if (finished != null && outboxService != null) {
                    if (finished.getStatus() == OrderStatus.COMPLETED) {
                        outboxService.saveOrderCompletedEvent(finished);
                    } else if (finished.getStatus() == OrderStatus.CANCELLED) {
                        outboxService.saveOrderCancelledEvent(finished);
                    } else if (finished.getStatus() == OrderStatus.FAILED) {
                        outboxService.saveOrderFailedEvent(finished);
                    }
                }
            } catch (Exception e) {
                log.error("Async saga execution failed for order id={}: {}", id, e.getMessage(), e);
            }
        };

        if (org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()) {
            org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                    new org.springframework.transaction.support.TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            CompletableFuture.runAsync(asyncExecution, sagaTaskExecutor);
                        }
                    }
            );
        } else {
            CompletableFuture.runAsync(asyncExecution, sagaTaskExecutor);
        }

        return orderMapper.toResponse(savedOrder);
    }

    public OrderResponse recoverOrder(UUID id) {
        CompositeOrder order = orchestrationService.recover(id);
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

    public OrderResponse compensateOrder(UUID id) {
        CompositeOrder order = orchestrationService.compensate(id);
        if (outboxService != null && order.getStatus() == OrderStatus.CANCELLED) {
            outboxService.saveOrderCancelledEvent(order);
        }
        return orderMapper.toResponse(order);
    }

    public OrderResponse modifyStep(UUID orderId, UUID stepId, BookingType type, CreateBookingStepRequest request) {
        if (request == null) {
            throw new InvalidBookingDataException("Step request must not be null");
        }

        validator.validateBookingStep(request);

        CompositeOrder order = repository.findById(orderId)
                .orElseThrow(() -> new OrderNotFoundException(orderId));

        if (order.getStatus() == OrderStatus.CANCELLED || order.getStatus() == OrderStatus.CANCELLING) {
            throw new InvalidStateTransitionException(
                    "Cannot modify order in status " + order.getStatus()
            );
        }

        BookingStep step;
        if (stepId != null) {
            step = order.findBookingStep(stepId)
                    .orElseThrow(() -> new InvalidStateTransitionException("Booking step not found: " + stepId));
        } else if (type != null) {
            step = order.getBookingSteps().stream()
                    .filter(s -> s.getType() == type)
                    .findFirst()
                    .orElseThrow(() -> new InvalidBookingDataException("Order does not contain a " + type + " booking step"));
        } else {
            step = order.getBookingSteps().stream()
                    .filter(s -> s.getType() == request.type())
                    .findFirst()
                    .orElseThrow(() -> new InvalidBookingDataException("Order does not contain a " + request.type() + " booking step"));
        }

        boolean isHotel = step.getType() == BookingType.HOTEL;
        final UUID targetStepId = step.getId();

        // Если шаг уже был подтвержден, выполняем внешнюю отмену вне транзакции БД (не держим соединение Hikari)
        if (step.getStatus() == BookingStatus.CONFIRMED && compensator != null) {
            String compReason = isHotel ? "Замена отеля на альтернативный" : "Модификация шага бронирования";
            String compUserMsg = isHotel
                    ? "Бронирование отеля отменено пользователем для выбора альтернативного отеля."
                    : "Бронирование услуги отменено пользователем для модификации шага.";

            compensator.compensateSingleStep(orderId, targetStepId, compReason, compUserMsg);
        }

        return applyStepModification(orderId, targetStepId, isHotel, request);
    }

    @Transactional
    public OrderResponse applyStepModification(UUID orderId, UUID targetStepId, boolean isHotel, CreateBookingStepRequest request) {
        CompositeOrder order = repository.findById(orderId)
                .orElseThrow(() -> new OrderNotFoundException(orderId));
        BookingStep step = order.findBookingStep(targetStepId)
                .orElseThrow(() -> new InvalidStateTransitionException("Booking step not found: " + targetStepId));

        BigDecimal price = request.price() != null ? request.price() : BigDecimal.ZERO;
        if (request.type() == BookingType.HOTEL && request.rooms() != null && !request.rooms().isEmpty()) {
            BigDecimal roomsTotal = request.rooms().stream()
                    .map(HotelRoomRequest::price)
                    .filter(Objects::nonNull)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            if (price.compareTo(BigDecimal.ZERO) == 0) {
                price = roomsTotal;
            }
        }

        order.replaceBookingStep(
                step.getId(),
                request.resourceId(),
                price,
                request.rooms()
        );

        String auditOp = isHotel ? "HOTEL_REPLACED" : "STEP_MODIFIED";
        String auditDesc = isHotel
                ? "Отель успешно заменен на альтернативный вариант (resourceId=" + request.resourceId() + "). Выкупленные билеты сохранены."
                : "Шаг бронирования (" + step.getType() + ") успешно модифицирован (resourceId=" + request.resourceId() + ").";

        order.recordOperation(
                step,
                step.getType(),
                auditOp,
                "PENDING",
                null,
                null,
                null,
                auditDesc,
                null,
                null
        );

        CompositeOrder savedOrder = repository.save(order);

        if (sseService != null) {
            sseService.publishStepStatus(orderId, step.getId(), step.getType(), BookingStatus.PENDING, null);
        }

        if (eventPublisher != null) {
            eventPublisher.publishEvent(new com.tamada.travel.orchestrator.event.BookingStepStatusChangedEvent(
                    orderId, step.getId(), step.getType(), BookingStatus.PENDING, null));
        }

        return orderMapper.toResponse(savedOrder);
    }

    @Transactional
    public OrderResponse replaceHotel(UUID orderId, ReplaceHotelRequest request) {
        if (request == null) {
            throw new InvalidBookingDataException("Replace hotel request must not be null");
        }
        if (request.type() != null && request.type() != BookingType.HOTEL) {
            throw new InvalidBookingDataException("Step type must be HOTEL for hotel replacement");
        }

        validator.validateHotelReplacement(
                request.resourceId(),
                request.price(),
                request.roomsCount(),
                request.rooms()
        );

        return modifyStep(orderId, null, BookingType.HOTEL, request.toCreateBookingStepRequest());
    }

    @Transactional
    public OrderResponse replaceStep(UUID orderId, UUID stepId, CreateBookingStepRequest request) {
        if (request == null) {
            throw new InvalidBookingDataException("Step request must not be null");
        }

        return modifyStep(orderId, stepId, request.type(), request);
    }

    public OrderResponse cancelStep(UUID orderId, UUID stepId) {
        CompositeOrder order = repository.findById(orderId)
                .orElseThrow(() -> new OrderNotFoundException(orderId));

        if (order.getStatus() == OrderStatus.CANCELLED || order.getStatus() == OrderStatus.CANCELLING) {
            throw new InvalidStateTransitionException(
                    "Cannot cancel step for order in status " + order.getStatus()
            );
        }

        BookingStep step = order.findBookingStep(stepId)
                .orElseThrow(() -> new InvalidStateTransitionException("Booking step not found: " + stepId));

        if (step.getStatus() == BookingStatus.CONFIRMED && compensator != null) {
            compensator.compensateSingleStep(
                    orderId,
                    step.getId(),
                    "Частичная отмена шага пользователем",
                    "Услуга (" + step.getType() + ") успешно отменена пользователем."
            );
            CompositeOrder saved = repository.save(order);
            return orderMapper.toResponse(saved);
        } else if (step.getStatus() == BookingStatus.PENDING || step.getStatus() == BookingStatus.FAILED) {
            return applyCancelSkippedStep(orderId, stepId);
        } else {
            throw new InvalidStateTransitionException("Cannot cancel step in status " + step.getStatus());
        }
    }

    @Transactional
    public OrderResponse applyCancelSkippedStep(UUID orderId, UUID stepId) {
        CompositeOrder order = repository.findById(orderId)
                .orElseThrow(() -> new OrderNotFoundException(orderId));
        BookingStep step = order.findBookingStep(stepId)
                .orElseThrow(() -> new InvalidStateTransitionException("Booking step not found: " + stepId));

        step.markSkipped("Отменено пользователем", "Услуга отменена пользователем.");
        order.recordOperation(
                step,
                step.getType(),
                "MANUAL_CANCEL",
                "CANCELLED",
                null,
                null,
                null,
                "Услуга (" + step.getType() + ") отменена пользователем.",
                null,
                "Отменено пользователем"
        );

        CompositeOrder saved = repository.save(order);
        return orderMapper.toResponse(saved);
    }
}
