package com.tamada.travel.orchestrator.service.orchestration;

import com.tamada.travel.orchestrator.exception.InvalidStateTransitionException;
import com.tamada.travel.orchestrator.observability.CorrelationIdContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Распределенная блокировка на базе PostgreSQL Advisory Locks (Cluster-wide Distributed Lock).
 * Гарантирует эксклюзивное выполнение операций оркестрации и компенсации заказа
 * между всеми подами/экземплярами оркестратора в кластере.
 * <p>
 * ВАЖНО: Захват (pg_try_advisory_lock) и освобождение (pg_advisory_unlock) выполняются строго
 * в рамках ОДНОГО И ТОГО ЖЕ физического соединения из пула через {@link ConnectionCallback},
 * что исключает утечку сессионных блокировок и взаимные блокировки при возврате соединений в HikariCP.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class PostgresAdvisorySagaLock implements SagaLock {

    private final JdbcTemplate jdbcTemplate;

    @Override
    public <T> T executeWithLock(UUID orderId, Duration timeout, Supplier<T> action) {
        long lockKey = orderId.getMostSignificantBits() ^ orderId.getLeastSignificantBits();

        try {
            return jdbcTemplate.execute((ConnectionCallback<T>) connection -> {
                boolean acquired = tryAcquireLock(connection, lockKey, orderId);
                if (!acquired) {
                    log.warn("Order {} is currently locked by another cluster node/process", orderId);
                    throw new InvalidStateTransitionException(
                            "Order " + orderId + " is currently locked by another orchestration operation");
                }

                CorrelationIdContext.setOrderId(orderId);
                try {
                    return action.get();
                } finally {
                    CorrelationIdContext.clearOrderId();
                    releaseLock(connection, lockKey, orderId);
                }
            });
        } catch (DataAccessException ex) {
            if (ex.getCause() instanceof InvalidStateTransitionException isx) {
                throw isx;
            }
            log.error("Database lock acquisition error for order {}: {}", orderId, ex.getMessage(), ex);
            throw new InvalidStateTransitionException("Database lock acquisition error for order " + orderId, ex);
        }
    }

    private boolean tryAcquireLock(Connection connection, long lockKey, UUID orderId) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("SELECT pg_try_advisory_lock(?)")) {
            ps.setLong(1, lockKey);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getBoolean(1);
                }
            }
        } catch (SQLException ex) {
            log.error("Failed to execute pg_try_advisory_lock for order {}: {}", orderId, ex.getMessage());
            throw ex;
        }
        return false;
    }

    private void releaseLock(Connection connection, long lockKey, UUID orderId) {
        try (PreparedStatement ps = connection.prepareStatement("SELECT pg_advisory_unlock(?)")) {
            ps.setLong(1, lockKey);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next() && !rs.getBoolean(1)) {
                    log.warn("pg_advisory_unlock returned false for order {} (key={})", orderId, lockKey);
                }
            }
        } catch (Exception ex) {
            log.error("Failed to release advisory lock for order {}: {}", orderId, ex.getMessage(), ex);
        }
    }
}
