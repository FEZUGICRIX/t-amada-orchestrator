package com.tamada.travel.orchestrator.repository;

import com.tamada.travel.orchestrator.model.OutboxMessage;
import com.tamada.travel.orchestrator.model.OutboxStatus;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Slice;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@Transactional
class OutboxRepositoryJpaTest {

    @Autowired
    private OutboxRepository outboxRepository;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @Test
    void shouldFetchSliceWithoutCountQueryAndReportHasNextCorrectly() {
        OutboxMessage m1 = new OutboxMessage("COMPOSITE_ORDER", "order-1", "ORDER_CREATED", "{}");
        OutboxMessage m2 = new OutboxMessage("COMPOSITE_ORDER", "order-2", "ORDER_PROCESSING", "{}");
        OutboxMessage m3 = new OutboxMessage("COMPOSITE_ORDER", "order-3", "ORDER_COMPLETED", "{}");
        outboxRepository.saveAll(List.of(m1, m2, m3));
        entityManager.flush();
        entityManager.clear();

        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        boolean prevStats = statistics.isStatisticsEnabled();
        statistics.setStatisticsEnabled(true);
        statistics.clear();

        try {
            Slice<OutboxMessage> firstSlice = outboxRepository.findByStatusOrderByCreatedAtAsc(
                    OutboxStatus.PENDING,
                    PageRequest.of(0, 2)
            );
            assertEquals(2, firstSlice.getNumberOfElements());
            assertTrue(firstSlice.hasNext());
            // Only 1 SELECT query (with LIMIT batchSize + 1), no SELECT count(*)
            assertEquals(1, statistics.getPrepareStatementCount());

            statistics.clear();
            Slice<OutboxMessage> secondSlice = outboxRepository.findByStatusOrderByCreatedAtAsc(
                    OutboxStatus.PENDING,
                    PageRequest.of(1, 2)
            );
            assertEquals(1, secondSlice.getNumberOfElements());
            assertFalse(secondSlice.hasNext());
            assertEquals(1, statistics.getPrepareStatementCount());
        } finally {
            statistics.setStatisticsEnabled(prevStats);
        }
    }

    @Test
    void shouldBatchUpdateStatusesByIdsInSingleQuery() {
        OutboxMessage m1 = new OutboxMessage("COMPOSITE_ORDER", "order-1", "ORDER_CREATED", "{}");
        OutboxMessage m2 = new OutboxMessage("COMPOSITE_ORDER", "order-2", "ORDER_PROCESSING", "{}");
        OutboxMessage m3 = new OutboxMessage("COMPOSITE_ORDER", "order-3", "ORDER_COMPLETED", "{}");
        outboxRepository.saveAll(List.of(m1, m2, m3));
        entityManager.flush();
        entityManager.clear();

        Instant processedAt = Instant.now();
        int updated = outboxRepository.updateStatusByIds(
                List.of(m1.getId(), m2.getId()),
                OutboxStatus.SENT,
                processedAt
        );
        entityManager.clear();

        assertEquals(2, updated);

        OutboxMessage reloaded1 = outboxRepository.findById(m1.getId()).orElseThrow();
        OutboxMessage reloaded2 = outboxRepository.findById(m2.getId()).orElseThrow();
        OutboxMessage reloaded3 = outboxRepository.findById(m3.getId()).orElseThrow();

        assertEquals(OutboxStatus.SENT, reloaded1.getStatus());
        assertNotNull(reloaded1.getProcessedAt());
        assertEquals(OutboxStatus.SENT, reloaded2.getStatus());
        assertNotNull(reloaded2.getProcessedAt());
        assertEquals(OutboxStatus.PENDING, reloaded3.getStatus());
        assertNull(reloaded3.getProcessedAt());
    }
}
