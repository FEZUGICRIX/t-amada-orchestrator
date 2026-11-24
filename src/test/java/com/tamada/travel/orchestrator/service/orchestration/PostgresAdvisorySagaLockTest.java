package com.tamada.travel.orchestrator.service.orchestration;

import com.tamada.travel.orchestrator.exception.InvalidStateTransitionException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PostgresAdvisorySagaLockTest {

    @Mock
    private JdbcTemplate jdbcTemplate;

    @Mock
    private Connection connection;

    @Mock
    private PreparedStatement psLock;

    @Mock
    private PreparedStatement psUnlock;

    @Mock
    private ResultSet rsLock;

    @Mock
    private ResultSet rsUnlock;

    private PostgresAdvisorySagaLock lock;

    @BeforeEach
    void setUp() {
        lock = new PostgresAdvisorySagaLock(jdbcTemplate);
    }

    @Test
    void shouldAcquireAndReleasePostgresAdvisoryLockSuccessfully() throws Exception {
        UUID orderId = UUID.randomUUID();

        when(connection.prepareStatement(contains("pg_try_advisory_lock"))).thenReturn(psLock);
        when(psLock.executeQuery()).thenReturn(rsLock);
        when(rsLock.next()).thenReturn(true);
        when(rsLock.getBoolean(1)).thenReturn(true);

        when(connection.prepareStatement(contains("pg_advisory_unlock"))).thenReturn(psUnlock);
        when(psUnlock.executeQuery()).thenReturn(rsUnlock);

        when(jdbcTemplate.execute(any(ConnectionCallback.class))).thenAnswer(invocation -> {
            ConnectionCallback<?> callback = invocation.getArgument(0);
            return callback.doInConnection(connection);
        });

        AtomicBoolean executed = new AtomicBoolean(false);
        String result = lock.executeWithLock(orderId, Duration.ZERO, () -> {
            executed.set(true);
            return "done";
        });

        assertEquals("done", result);
        assertTrue(executed.get());
        verify(psLock).executeQuery();
        verify(psUnlock).executeQuery();
    }

    @Test
    void shouldThrowInvalidStateTransitionExceptionWhenLockNotAcquired() throws Exception {
        UUID orderId = UUID.randomUUID();

        when(connection.prepareStatement(contains("pg_try_advisory_lock"))).thenReturn(psLock);
        when(psLock.executeQuery()).thenReturn(rsLock);
        when(rsLock.next()).thenReturn(true);
        when(rsLock.getBoolean(1)).thenReturn(false);

        when(jdbcTemplate.execute(any(ConnectionCallback.class))).thenAnswer(invocation -> {
            ConnectionCallback<?> callback = invocation.getArgument(0);
            return callback.doInConnection(connection);
        });

        assertThrows(InvalidStateTransitionException.class, () ->
                lock.executeWithLock(orderId, Duration.ZERO, () -> "should-not-run")
        );

        verify(connection, never()).prepareStatement(contains("pg_advisory_unlock"));
    }
}
