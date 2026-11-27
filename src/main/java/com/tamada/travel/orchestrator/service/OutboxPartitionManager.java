package com.tamada.travel.orchestrator.service;

import com.tamada.travel.orchestrator.config.OutboxPartitionProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.Connection;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Управляет жизненным циклом ежедневных RANGE-партиций таблицы {@code outbox_messages} в PostgreSQL:
 * <ul>
 *     <li>Заранее создаёт партиции на {@code precreateDays} дней вперёд (UTC);</li>
 *     <li>Отсоединяет и удаляет/архивирует устаревшие партиции старше {@code retentionDays} дней
 *     через {@code DETACH PARTITION ... CONCURRENTLY} без блокировок и раздувания таблицы (table bloat),
 *     предварительно убедившись, что в партиции не осталось неотправленных сообщений.</li>
 * </ul>
 * <p>
 * Важно: методы не должны быть помечены {@code @Transactional}, так как PostgreSQL запрещает
 * выполнение {@code ALTER TABLE ... DETACH PARTITION ... CONCURRENTLY} внутри транзакционного блока.
 */
@Service
@EnableConfigurationProperties(OutboxPartitionProperties.class)
@Slf4j
public class OutboxPartitionManager {

    static final String PARENT_TABLE = "outbox_messages";
    static final long ADVISORY_LOCK_KEY = 726491830241L;

    private static final DateTimeFormatter PARTITION_DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy_MM_dd");
    private static final Pattern PARTITION_NAME_PATTERN = Pattern.compile("^outbox_messages_(\\d{4}_\\d{2}_\\d{2})$");
    private static final Pattern VALID_IDENTIFIER_PATTERN = Pattern.compile("^[a-zA-Z_][a-zA-Z0-9_]*$");

    private final JdbcTemplate jdbcTemplate;
    private final OutboxPartitionProperties properties;
    private final Clock clock;

    @Autowired
    public OutboxPartitionManager(JdbcTemplate jdbcTemplate, OutboxPartitionProperties properties) {
        this(jdbcTemplate, properties, Clock.systemUTC());
    }

    OutboxPartitionManager(JdbcTemplate jdbcTemplate, OutboxPartitionProperties properties, Clock clock) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate must not be null");
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        if (properties.enabled()) {
            maintainPartitions();
        }
    }

    @Scheduled(
            fixedDelayString = "${outbox.partition.fixed-delay:1h}",
            initialDelayString = "${outbox.partition.initial-delay:30s}"
    )
    public void scheduledMaintainPartitions() {
        if (properties.enabled()) {
            maintainPartitions();
        }
    }

    public void maintainPartitions() {
        if (!isPostgresDatabase()) {
            log.debug("Skipping outbox partition maintenance: non-PostgreSQL database detected");
            return;
        }

        Boolean lockAcquired = jdbcTemplate.queryForObject(
                "SELECT pg_try_advisory_lock(?)",
                Boolean.class,
                ADVISORY_LOCK_KEY
        );
        if (!Boolean.TRUE.equals(lockAcquired)) {
            log.debug("Another instance holds outbox partition maintenance advisory lock, skipping");
            return;
        }

        try {
            ensureFuturePartitions();
            cleanupExpiredPartitions();
        } finally {
            try {
                jdbcTemplate.queryForObject("SELECT pg_advisory_unlock(?)", Boolean.class, ADVISORY_LOCK_KEY);
            } catch (Exception ex) {
                log.warn("Failed to release outbox partition advisory lock: {}", ex.getMessage());
            }
        }
    }

    protected void ensureFuturePartitions() {
        LocalDate todayUtc = LocalDate.now(clock.withZone(ZoneOffset.UTC));
        for (int offset = 0; offset <= properties.precreateDays(); offset++) {
            LocalDate targetDay = todayUtc.plusDays(offset);
            String partitionName = partitionNameFor(targetDay);
            String fromBound = targetDay.atStartOfDay(ZoneOffset.UTC).toString();
            String toBound = targetDay.plusDays(1).atStartOfDay(ZoneOffset.UTC).toString();

            String sql = String.format(
                    "CREATE TABLE IF NOT EXISTS %s PARTITION OF %s FOR VALUES FROM ('%s') TO ('%s')",
                    partitionName, PARENT_TABLE, fromBound, toBound
            );
            try {
                jdbcTemplate.execute(sql);
                log.debug("Ensured outbox partition {} [{}, {})", partitionName, fromBound, toBound);
            } catch (Exception ex) {
                log.warn("Could not create outbox partition {}: {}", partitionName, ex.getMessage());
            }
        }
    }

    protected void cleanupExpiredPartitions() {
        LocalDate cutoffDate = LocalDate.now(clock.withZone(ZoneOffset.UTC)).minusDays(properties.retentionDays());
        List<String> partitions = jdbcTemplate.queryForList(
                """
                SELECT c.relname
                FROM pg_inherits i
                JOIN pg_class p ON p.oid = i.inhparent
                JOIN pg_class c ON c.oid = i.inhrelid
                JOIN pg_namespace n ON n.oid = p.relnamespace
                WHERE n.nspname = current_schema()
                  AND p.relname = ?
                ORDER BY c.relname ASC
                """,
                String.class,
                PARENT_TABLE
        );

        for (String partitionName : partitions) {
            Matcher matcher = PARTITION_NAME_PATTERN.matcher(partitionName);
            if (!matcher.matches()) {
                continue;
            }

            LocalDate partitionDate = LocalDate.parse(matcher.group(1), PARTITION_DATE_FORMAT);
            if (!partitionDate.isBefore(cutoffDate)) {
                continue;
            }

            if (hasNonSentMessages(partitionName)) {
                log.warn("Skipping cleanup of outbox partition {} because it still contains non-SENT events", partitionName);
                continue;
            }

            detachAndDisposePartition(partitionName);
        }
    }

    private boolean hasNonSentMessages(String partitionName) {
        String safePartition = requireValidIdentifier(partitionName);
        Boolean exists = jdbcTemplate.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM " + safePartition + " WHERE status <> 'SENT')",
                Boolean.class
        );
        return Boolean.TRUE.equals(exists);
    }

    private void detachAndDisposePartition(String partitionName) {
        String safePartition = requireValidIdentifier(partitionName);

        // DETACH PARTITION ... CONCURRENTLY не блокирует чтение/запись в родительскую таблицу outbox_messages
        jdbcTemplate.execute("ALTER TABLE " + PARENT_TABLE + " DETACH PARTITION " + safePartition + " CONCURRENTLY");

        if (properties.cleanupStrategy() == OutboxPartitionProperties.CleanupStrategy.ARCHIVE) {
            String safeSchema = requireValidIdentifier(properties.archiveSchema());
            jdbcTemplate.execute("CREATE SCHEMA IF NOT EXISTS " + safeSchema);
            jdbcTemplate.execute("ALTER TABLE " + safePartition + " SET SCHEMA " + safeSchema);
            log.info("Detached and archived expired outbox partition {} to schema {}", safePartition, safeSchema);
        } else {
            jdbcTemplate.execute("DROP TABLE IF EXISTS " + safePartition);
            log.info("Detached and dropped expired outbox partition {}", safePartition);
        }
    }

    protected boolean isPostgresDatabase() {
        DataSource dataSource = jdbcTemplate.getDataSource();
        if (dataSource == null) {
            return false;
        }
        try (Connection connection = dataSource.getConnection()) {
            String productName = connection.getMetaData().getDatabaseProductName();
            return productName != null && productName.toLowerCase().contains("postgresql");
        } catch (Exception ex) {
            log.debug("Failed to detect database product name: {}", ex.getMessage());
            return false;
        }
    }

    static String partitionNameFor(LocalDate utcDate) {
        return "outbox_messages_" + PARTITION_DATE_FORMAT.format(utcDate);
    }

    private static String requireValidIdentifier(String identifier) {
        if (identifier == null || !VALID_IDENTIFIER_PATTERN.matcher(identifier).matches()) {
            throw new IllegalArgumentException("Invalid SQL identifier: " + identifier);
        }
        return identifier;
    }
}
