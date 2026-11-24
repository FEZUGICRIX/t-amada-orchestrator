package com.tamada.travel.orchestrator.observability;

import com.tamada.travel.orchestrator.model.OutboxStatus;
import com.tamada.travel.orchestrator.repository.OutboxRepository;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Индикатор состояния Saga Orchestrator для Spring Boot Actuator (/actuator/health).
 * Проверяет состояние цепей Circuit Breaker и наличие застрявших сообщений в очереди Outbox.
 */
@Component("sagaOrchestrator")
@RequiredArgsConstructor
public class SagaOrchestratorHealthIndicator implements HealthIndicator {

    private final OutboxRepository outboxRepository;

    @Autowired(required = false)
    private CircuitBreakerRegistry circuitBreakerRegistry;

    @Override
    public Health health() {
        Health.Builder builder = Health.up();
        Map<String, Object> details = new LinkedHashMap<>();

        // Проверяем состояние Circuit Breakers
        boolean anyCircuitOpen = false;
        if (circuitBreakerRegistry != null) {
            Map<String, String> cbStates = new LinkedHashMap<>();
            for (CircuitBreaker cb : circuitBreakerRegistry.getAllCircuitBreakers()) {
                CircuitBreaker.State state = cb.getState();
                cbStates.put(cb.getName(), state.name());
                if (state == CircuitBreaker.State.OPEN || state == CircuitBreaker.State.FORCED_OPEN) {
                    anyCircuitOpen = true;
                }
            }
            details.put("circuitBreakers", cbStates);
        }

        // Проверяем наличие сообщений в очереди Outbox
        try {
            int pendingCount = outboxRepository.findByStatusOrderByCreatedAtAsc(
                    OutboxStatus.PENDING,
                    PageRequest.of(0, 100)
            ).size();
            details.put("outboxPendingBatchCount", pendingCount);
        } catch (Exception e) {
            details.put("outboxCheckError", e.getMessage());
        }

        if (anyCircuitOpen) {
            builder.status("CIRCUIT_OPEN").withDetails(details);
        } else {
            builder.withDetails(details);
        }

        return builder.build();
    }
}
