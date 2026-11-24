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
import java.sql.DatabaseMetaData;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DistributedSagaLockTest {

    @Mock
    private JdbcTemplate jdbcTemplate;

    @Mock
    private PostgresAdvisorySagaLock postgresAdvisoryLock;

    @Mock
    private Connection connection;

    @Mock
    private DatabaseMetaData metaData;

    private SagaExecutionLock inMemoryLock;
    private DistributedSagaLock distributedLock;

    @BeforeEach
    void setUp() {
        inMemoryLock = new SagaExecutionLock();
        distributedLock = new DistributedSagaLock(inMemoryLock, postgresAdvisoryLock, jdbcTemplate);
    }

    @Test
    void shouldDelegateToPostgresAdvisoryLockWhenDatabaseIsPostgres() throws Exception {
        when(metaData.getDatabaseProductName()).thenReturn("PostgreSQL 17.0");
        when(connection.getMetaData()).thenReturn(metaData);
        when(jdbcTemplate.execute(any(ConnectionCallback.class))).thenAnswer(invocation -> {
            ConnectionCallback<?> callback = invocation.getArgument(0);
            return callback.doInConnection(connection);
        });

        UUID orderId = UUID.randomUUID();
        when(postgresAdvisoryLock.executeWithLock(eq(orderId), eq(Duration.ZERO), any(java.util.function.Supplier.class)))
                .thenReturn("success");

        String result = distributedLock.executeWithLock(orderId, () -> "success");

        assertEquals("success", result);
        verify(postgresAdvisoryLock).executeWithLock(eq(orderId), eq(Duration.ZERO), any(java.util.function.Supplier.class));
    }

    @Test
    void shouldFallbackToInMemoryLockWhenDatabaseIsNotPostgres() throws Exception {
        when(metaData.getDatabaseProductName()).thenReturn("H2");
        when(connection.getMetaData()).thenReturn(metaData);
        when(jdbcTemplate.execute(any(ConnectionCallback.class))).thenAnswer(invocation -> {
            ConnectionCallback<?> callback = invocation.getArgument(0);
            return callback.doInConnection(connection);
        });

        UUID orderId = UUID.randomUUID();
        AtomicBoolean executed = new AtomicBoolean(false);

        distributedLock.executeWithLock(orderId, () -> {
            executed.set(true);
            return null;
        });

        assertTrue(executed.get());
        verifyNoInteractions(postgresAdvisoryLock);
    }
}
