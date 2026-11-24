package com.tamada.travel.orchestrator.observability;

import org.slf4j.MDC;

import java.util.UUID;

/**
 * Контекст управления идентификаторами корреляции (Correlation ID / Trace ID).
 * Обеспечивает сквозную трассировку запросов и операций оркестрации через SLF4J MDC.
 */
public final class CorrelationIdContext {

    public static final String HEADER_CORRELATION_ID = "X-Correlation-Id";
    public static final String HEADER_REQUEST_ID = "X-Request-Id";
    public static final String HEADER_ORDER_ID = "X-Order-Id";

    public static final String MDC_CORRELATION_ID = "correlationId";
    public static final String MDC_ORDER_ID = "orderId";

    private CorrelationIdContext() {
    }

    public static String getCorrelationId() {
        return MDC.get(MDC_CORRELATION_ID);
    }

    public static void setCorrelationId(String correlationId) {
        if (correlationId != null && !correlationId.isBlank()) {
            MDC.put(MDC_CORRELATION_ID, correlationId.trim());
        }
    }

    public static String getOrCreateCorrelationId() {
        String existing = getCorrelationId();
        if (existing == null || existing.isBlank()) {
            existing = UUID.randomUUID().toString();
            setCorrelationId(existing);
        }
        return existing;
    }

    public static void setOrderId(UUID orderId) {
        if (orderId != null) {
            MDC.put(MDC_ORDER_ID, orderId.toString());
        }
    }

    public static String getOrderId() {
        return MDC.get(MDC_ORDER_ID);
    }

    public static void clearOrderId() {
        MDC.remove(MDC_ORDER_ID);
    }

    public static void clear() {
        MDC.remove(MDC_CORRELATION_ID);
        MDC.remove(MDC_ORDER_ID);
    }
}
