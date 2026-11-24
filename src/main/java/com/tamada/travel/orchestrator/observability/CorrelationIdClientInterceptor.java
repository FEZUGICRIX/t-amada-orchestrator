package com.tamada.travel.orchestrator.observability;

import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.security.SecureRandom;
import java.util.UUID;

/**
 * Исходящий HTTP-перехватчик для клиентов бронирования (RestBookingClient).
 * Транслирует сквозной Correlation ID, Order ID и стандартные W3C Traceparent
 * заголовки во внешние микросервисы (Flight, Hotel, Insurance, Transfer).
 */
@Component
public class CorrelationIdClientInterceptor implements ClientHttpRequestInterceptor {

    public static final String HEADER_TRACEPARENT = "traceparent";
    private static final SecureRandom RANDOM = new SecureRandom();

    @Override
    public ClientHttpResponse intercept(
            HttpRequest request,
            byte[] body,
            ClientHttpRequestExecution execution
    ) throws IOException {
        String correlationId = CorrelationIdContext.getCorrelationId();
        if (correlationId == null || correlationId.isBlank()) {
            correlationId = UUID.randomUUID().toString();
        }

        if (request.getHeaders().getFirst(CorrelationIdContext.HEADER_CORRELATION_ID) == null) {
            request.getHeaders().add(CorrelationIdContext.HEADER_CORRELATION_ID, correlationId);
        }

        if (request.getHeaders().getFirst(CorrelationIdContext.HEADER_REQUEST_ID) == null) {
            request.getHeaders().add(CorrelationIdContext.HEADER_REQUEST_ID, correlationId);
        }

        String orderId = CorrelationIdContext.getOrderId();
        if (orderId != null && !orderId.isBlank() && request.getHeaders().getFirst(CorrelationIdContext.HEADER_ORDER_ID) == null) {
            request.getHeaders().add(CorrelationIdContext.HEADER_ORDER_ID, orderId);
        }

        if (request.getHeaders().getFirst(HEADER_TRACEPARENT) == null) {
            request.getHeaders().add(HEADER_TRACEPARENT, generateW3cTraceparent(correlationId));
        }

        return execution.execute(request, body);
    }

    private String generateW3cTraceparent(String correlationId) {
        String clean = correlationId != null ? correlationId.replace("-", "").toLowerCase() : "";
        String traceId;
        if (clean.matches("^[0-9a-f]{32}$") && !clean.equals("00000000000000000000000000000000")) {
            traceId = clean;
        } else {
            try {
                java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
                byte[] hash = md.digest(correlationId != null ? correlationId.getBytes(java.nio.charset.StandardCharsets.UTF_8) : new byte[0]);
                traceId = java.util.HexFormat.of().formatHex(hash, 0, 16);
            } catch (Exception e) {
                traceId = UUID.randomUUID().toString().replace("-", "");
            }
        }

        byte[] spanBytes = new byte[8];
        RANDOM.nextBytes(spanBytes);
        StringBuilder spanId = new StringBuilder(16);
        for (byte b : spanBytes) {
            spanId.append(String.format("%02x", b));
        }

        return "00-" + traceId + "-" + spanId + "-01";
    }
}
