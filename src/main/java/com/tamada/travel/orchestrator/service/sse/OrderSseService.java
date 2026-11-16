package com.tamada.travel.orchestrator.service.sse;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.tamada.travel.orchestrator.dto.error.StepErrorDetailsResponse;
import com.tamada.travel.orchestrator.model.BookingStatus;
import com.tamada.travel.orchestrator.model.BookingType;
import com.tamada.travel.orchestrator.model.CompositeOrder;
import com.tamada.travel.orchestrator.model.OrderStatus;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Сервис управления подписками и отправки Server-Sent Events (SSE) клиентам.
 * Передает события статуса заказа, шагов бронирования, детальных ошибок поставщиков и причин компенсации.
 */
@Service
@Slf4j
public class OrderSseService {

    private static final long DEFAULT_TIMEOUT_MS = Duration.ofMinutes(10).toMillis();

    private final Map<UUID, List<SseEmitter>> emitters = new ConcurrentHashMap<>();

    /**
     * Создает подписку на события заказа для клиента.
     */
    public SseEmitter subscribe(UUID orderId, CompositeOrder currentOrder) {
        SseEmitter emitter = new SseEmitter(DEFAULT_TIMEOUT_MS);

        emitters.computeIfAbsent(orderId, k -> new CopyOnWriteArrayList<>()).add(emitter);

        emitter.onCompletion(() -> removeEmitter(orderId, emitter));
        emitter.onTimeout(() -> removeEmitter(orderId, emitter));
        emitter.onError(e -> removeEmitter(orderId, emitter));

        // Отправляем начальное событие подключения
        sendToEmitter(emitter, OrderEvent.of("connected", orderId, Map.of(
                "orderId", orderId,
                "message", "Subscribed to order events",
                "currentStatus", currentOrder != null ? currentOrder.getStatus().name() : "UNKNOWN",
                "timestamp", Instant.now().toString()
        )));

        // Если заказ уже в терминальном состоянии, отправляем текущее состояние и закрываем поток
        if (currentOrder != null) {
            OrderStatus status = currentOrder.getStatus();
            if (status == OrderStatus.COMPLETED) {
                sendToEmitter(emitter, OrderEvent.of("order-completed", orderId, Map.of(
                        "orderId", orderId,
                        "status", OrderStatus.COMPLETED.name(),
                        "timestamp", Instant.now().toString()
                )));
                emitter.complete();
            } else if (status == OrderStatus.CANCELLED) {
                sendToEmitter(emitter, OrderEvent.of("order-cancelled", orderId, Map.of(
                        "orderId", orderId,
                        "status", OrderStatus.CANCELLED.name(),
                        "failureReason", currentOrder.getFailureReason() != null ? currentOrder.getFailureReason() : "",
                        "userMessage", currentOrder.getUserMessage() != null ? currentOrder.getUserMessage() : "",
                        "timestamp", Instant.now().toString()
                )));
                emitter.complete();
            }
        }

        return emitter;
    }

    public void publishOrderStatus(UUID orderId, OrderStatus status) {
        publish(orderId, OrderEvent.of("order-status", orderId, Map.of(
                "orderId", orderId,
                "status", status.name(),
                "timestamp", Instant.now().toString()
        )));
    }

    public void publishStepStatus(UUID orderId, UUID stepId, BookingType type, BookingStatus status, String externalId) {
        publish(orderId, OrderEvent.of("step-status", orderId, new StepStatusPayload(
                orderId,
                stepId,
                type,
                status.name(),
                externalId,
                Instant.now()
        )));
    }

    public void publishStepFailed(
            UUID orderId,
            UUID stepId,
            BookingType type,
            String errorCode,
            Integer httpStatus,
            String errorMessage,
            String userMessage,
            StepErrorDetailsResponse details
    ) {
        publish(orderId, OrderEvent.of("step-failed", orderId, new StepFailedPayload(
                orderId,
                stepId,
                type,
                BookingStatus.FAILED.name(),
                errorCode,
                httpStatus,
                errorMessage,
                userMessage,
                details,
                Instant.now()
        )));
    }

    public void publishStepCancelled(
            UUID orderId,
            UUID stepId,
            BookingType type,
            BookingStatus status,
            String cancellationReason,
            String userMessage
    ) {
        publish(orderId, OrderEvent.of("step-cancelled", orderId, new StepCancelledPayload(
                orderId,
                stepId,
                type,
                status.name(),
                cancellationReason,
                userMessage,
                Instant.now()
        )));
    }

    public void publishOrderCompleted(UUID orderId) {
        publish(orderId, OrderEvent.of("order-completed", orderId, Map.of(
                "orderId", orderId,
                "status", OrderStatus.COMPLETED.name(),
                "timestamp", Instant.now().toString()
        )));
        completeEmitters(orderId);
    }

    public void publishOrderCancelled(UUID orderId, String failureReason, String userMessage) {
        publish(orderId, OrderEvent.of("order-cancelled", orderId, Map.of(
                "orderId", orderId,
                "status", OrderStatus.CANCELLED.name(),
                "failureReason", failureReason != null ? failureReason : "",
                "userMessage", userMessage != null ? userMessage : "",
                "timestamp", Instant.now().toString()
        )));
        completeEmitters(orderId);
    }

    private void publish(UUID orderId, OrderEvent event) {
        List<SseEmitter> orderEmitters = emitters.get(orderId);
        if (orderEmitters == null || orderEmitters.isEmpty()) {
            return;
        }

        for (SseEmitter emitter : orderEmitters) {
            sendToEmitter(emitter, event);
        }
    }

    private void sendToEmitter(SseEmitter emitter, OrderEvent event) {
        try {
            emitter.send(SseEmitter.event()
                    .name(event.eventType())
                    .id(UUID.randomUUID().toString())
                    .data(event.data()));
        } catch (IOException | IllegalStateException e) {
            log.debug("Failed to send SSE event to emitter: {}", e.getMessage());
            emitter.completeWithError(e);
        }
    }

    private void completeEmitters(UUID orderId) {
        List<SseEmitter> orderEmitters = emitters.remove(orderId);
        if (orderEmitters != null) {
            for (SseEmitter emitter : orderEmitters) {
                try {
                    emitter.complete();
                } catch (Exception ignored) {
                }
            }
        }
    }

    private void removeEmitter(UUID orderId, SseEmitter emitter) {
        List<SseEmitter> orderEmitters = emitters.get(orderId);
        if (orderEmitters != null) {
            orderEmitters.remove(emitter);
            if (orderEmitters.isEmpty()) {
                emitters.remove(orderId, orderEmitters);
            }
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record StepStatusPayload(
            UUID orderId,
            UUID stepId,
            BookingType type,
            String status,
            String externalId,
            Instant timestamp
    ) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record StepFailedPayload(
            UUID orderId,
            UUID stepId,
            BookingType type,
            String status,
            String errorCode,
            Integer httpStatus,
            String errorMessage,
            String userMessage,
            StepErrorDetailsResponse errorDetails,
            Instant timestamp
    ) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record StepCancelledPayload(
            UUID orderId,
            UUID stepId,
            BookingType type,
            String status,
            String cancellationReason,
            String userMessage,
            Instant timestamp
    ) {
    }
}
