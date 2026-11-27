package com.tamada.travel.orchestrator.service;

import com.tamada.travel.orchestrator.config.OutboxPartitionProperties;
import com.tamada.travel.orchestrator.config.OutboxPartitionProperties.CleanupStrategy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OutboxPartitionManagerTest {

    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2026-11-27T12:00:00Z"), ZoneOffset.UTC);

    @Mock
    private JdbcTemplate jdbcTemplate;

    @Mock
    private DataSource dataSource;

    @Mock
    private Connection connection;

    @Mock
    private DatabaseMetaData metaData;

    private OutboxPartitionProperties dropProperties;

    @BeforeEach
    void setUp() {
        dropProperties = new OutboxPartitionProperties(
                true,
                2,
                7,
                CleanupStrategy.DROP,
                "outbox_archive",
                Duration.ofHours(1),
                Duration.ofSeconds(30)
        );
    }

    private void stubDatabaseProduct(String productName) throws Exception {
        when(jdbcTemplate.getDataSource()).thenReturn(dataSource);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.getMetaData()).thenReturn(metaData);
        when(metaData.getDatabaseProductName()).thenReturn(productName);
    }

    @Test
    void shouldSkipMaintenanceOnNonPostgresDatabase() throws Exception {
        stubDatabaseProduct("H2");
        OutboxPartitionManager manager = new OutboxPartitionManager(jdbcTemplate, dropProperties, FIXED_CLOCK);

        manager.maintainPartitions();

        verify(jdbcTemplate, never()).queryForObject(contains("pg_try_advisory_lock"), eq(Boolean.class), anyLong());
        verify(jdbcTemplate, never()).execute(anyString());
    }

    @Test
    void shouldSkipMaintenanceWhenAdvisoryLockNotAcquired() throws Exception {
        stubDatabaseProduct("PostgreSQL");
        when(jdbcTemplate.queryForObject("SELECT pg_try_advisory_lock(?)", Boolean.class, OutboxPartitionManager.ADVISORY_LOCK_KEY))
                .thenReturn(false);

        OutboxPartitionManager manager = new OutboxPartitionManager(jdbcTemplate, dropProperties, FIXED_CLOCK);
        manager.maintainPartitions();

        verify(jdbcTemplate, never()).execute(anyString());
        verify(jdbcTemplate, never()).queryForObject(eq("SELECT pg_advisory_unlock(?)"), eq(Boolean.class), anyLong());
    }

    @Test
    void shouldCreateFuturePartitionsAndDropOnlyExpiredAllSentPartitions() throws Exception {
        stubDatabaseProduct("PostgreSQL");
        when(jdbcTemplate.queryForObject("SELECT pg_try_advisory_lock(?)", Boolean.class, OutboxPartitionManager.ADVISORY_LOCK_KEY))
                .thenReturn(true);
        when(jdbcTemplate.queryForObject("SELECT pg_advisory_unlock(?)", Boolean.class, OutboxPartitionManager.ADVISORY_LOCK_KEY))
                .thenReturn(true);

        // Today is 2026-11-27, retentionDays = 7 -> cutoff = 2026-11-20
        // 2026_11_18 -> expired, all SENT -> detach & drop
        // 2026_11_19 -> expired, has non-SENT -> skip
        // 2026_11_20 -> not expired (equal to cutoff) -> skip
        // outbox_messages_default -> skip
        when(jdbcTemplate.queryForList(anyString(), eq(String.class), eq("outbox_messages")))
                .thenReturn(List.of(
                        "outbox_messages_2026_11_18",
                        "outbox_messages_2026_11_19",
                        "outbox_messages_2026_11_20",
                        "outbox_messages_default"
                ));

        when(jdbcTemplate.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM outbox_messages_2026_11_18 WHERE status <> 'SENT')",
                Boolean.class
        )).thenReturn(false);
        when(jdbcTemplate.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM outbox_messages_2026_11_19 WHERE status <> 'SENT')",
                Boolean.class
        )).thenReturn(true);

        OutboxPartitionManager manager = new OutboxPartitionManager(jdbcTemplate, dropProperties, FIXED_CLOCK);
        manager.maintainPartitions();

        // Future partitions for today + 2 days
        verify(jdbcTemplate).execute(
                "CREATE TABLE IF NOT EXISTS outbox_messages_2026_11_27 PARTITION OF outbox_messages FOR VALUES FROM ('2026-11-27T00:00Z') TO ('2026-11-28T00:00Z')"
        );
        verify(jdbcTemplate).execute(
                "CREATE TABLE IF NOT EXISTS outbox_messages_2026_11_28 PARTITION OF outbox_messages FOR VALUES FROM ('2026-11-28T00:00Z') TO ('2026-11-29T00:00Z')"
        );
        verify(jdbcTemplate).execute(
                "CREATE TABLE IF NOT EXISTS outbox_messages_2026_11_29 PARTITION OF outbox_messages FOR VALUES FROM ('2026-11-29T00:00Z') TO ('2026-11-30T00:00Z')"
        );

        // Expired & all-SENT partition detached concurrently and dropped
        verify(jdbcTemplate).execute("ALTER TABLE outbox_messages DETACH PARTITION outbox_messages_2026_11_18 CONCURRENTLY");
        verify(jdbcTemplate).execute("DROP TABLE IF EXISTS outbox_messages_2026_11_18");

        // Partition with unsent rows, non-expired partition, and default partition are untouched
        verify(jdbcTemplate, never()).execute(contains("outbox_messages_2026_11_19"));
        verify(jdbcTemplate, never()).execute(contains("outbox_messages_2026_11_20"));
        verify(jdbcTemplate, never()).execute(contains("outbox_messages_default"));

        // Advisory lock released
        verify(jdbcTemplate).queryForObject("SELECT pg_advisory_unlock(?)", Boolean.class, OutboxPartitionManager.ADVISORY_LOCK_KEY);
    }

    @Test
    void shouldArchiveExpiredPartitionWhenArchiveStrategyConfigured() throws Exception {
        stubDatabaseProduct("PostgreSQL");
        when(jdbcTemplate.queryForObject("SELECT pg_try_advisory_lock(?)", Boolean.class, OutboxPartitionManager.ADVISORY_LOCK_KEY))
                .thenReturn(true);
        when(jdbcTemplate.queryForObject("SELECT pg_advisory_unlock(?)", Boolean.class, OutboxPartitionManager.ADVISORY_LOCK_KEY))
                .thenReturn(true);
        when(jdbcTemplate.queryForList(anyString(), eq(String.class), eq("outbox_messages")))
                .thenReturn(List.of("outbox_messages_2026_11_10"));
        when(jdbcTemplate.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM outbox_messages_2026_11_10 WHERE status <> 'SENT')",
                Boolean.class
        )).thenReturn(false);

        OutboxPartitionProperties archiveProperties = new OutboxPartitionProperties(
                true,
                1,
                7,
                CleanupStrategy.ARCHIVE,
                "outbox_archive",
                Duration.ofHours(1),
                Duration.ofSeconds(30)
        );
        OutboxPartitionManager manager = new OutboxPartitionManager(jdbcTemplate, archiveProperties, FIXED_CLOCK);
        manager.maintainPartitions();

        verify(jdbcTemplate).execute("ALTER TABLE outbox_messages DETACH PARTITION outbox_messages_2026_11_10 CONCURRENTLY");
        verify(jdbcTemplate).execute("CREATE SCHEMA IF NOT EXISTS outbox_archive");
        verify(jdbcTemplate).execute("ALTER TABLE outbox_messages_2026_11_10 SET SCHEMA outbox_archive");
        verify(jdbcTemplate, never()).execute(contains("DROP TABLE"));
    }
}
