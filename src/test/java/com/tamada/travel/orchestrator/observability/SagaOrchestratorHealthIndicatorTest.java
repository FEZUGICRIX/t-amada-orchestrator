package com.tamada.travel.orchestrator.observability;

import com.tamada.travel.orchestrator.model.OutboxMessage;
import com.tamada.travel.orchestrator.model.OutboxStatus;
import com.tamada.travel.orchestrator.repository.OutboxRepository;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.health.contributor.Health;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.SliceImpl;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SagaOrchestratorHealthIndicatorTest {

    @Mock
    private OutboxRepository outboxRepository;

    private CircuitBreakerRegistry registry;
    private SagaOrchestratorHealthIndicator healthIndicator;

    @BeforeEach
    void setUp() {
        registry = CircuitBreakerRegistry.of(CircuitBreakerConfig.ofDefaults());
        healthIndicator = new SagaOrchestratorHealthIndicator(outboxRepository);
        ReflectionTestUtils.setField(healthIndicator, "circuitBreakerRegistry", registry);
    }

    @Test
    void shouldReportUpWhenCircuitBreakersClosed() {
        registry.circuitBreaker("FLIGHT");
        registry.circuitBreaker("HOTEL");

        when(outboxRepository.findByStatusOrderByCreatedAtAsc(eq(OutboxStatus.PENDING), any(Pageable.class)))
                .thenReturn(new SliceImpl<>(List.of()));

        Health health = healthIndicator.health();

        assertEquals("UP", health.getStatus().getCode());
        assertNotNull(health.getDetails().get("circuitBreakers"));
        assertEquals(0, health.getDetails().get("outboxPendingBatchCount"));
    }

    @Test
    void shouldReportCircuitOpenWhenAnyCircuitBreakerIsOpen() {
        CircuitBreaker cb = registry.circuitBreaker("HOTEL");
        cb.transitionToOpenState();

        when(outboxRepository.findByStatusOrderByCreatedAtAsc(eq(OutboxStatus.PENDING), any(Pageable.class)))
                .thenReturn(new SliceImpl<>(List.of(new OutboxMessage("ORDER", "1", "ORDER_CREATED", "{}"))));

        Health health = healthIndicator.health();

        assertEquals("CIRCUIT_OPEN", health.getStatus().getCode());
        assertEquals(1, health.getDetails().get("outboxPendingBatchCount"));
    }
}
