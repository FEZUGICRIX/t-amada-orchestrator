package com.tamada.travel.orchestrator.service.sse;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.UUID;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record OrderEvent(
        String eventType,
        UUID orderId,
        Instant timestamp,
        Object data
) {
    public static OrderEvent of(String eventType, UUID orderId, Object data) {
        return new OrderEvent(eventType, orderId, Instant.now(), data);
    }
}
