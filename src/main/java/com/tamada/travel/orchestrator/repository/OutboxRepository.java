package com.tamada.travel.orchestrator.repository;

import com.tamada.travel.orchestrator.model.OutboxMessage;
import com.tamada.travel.orchestrator.model.OutboxStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface OutboxRepository extends JpaRepository<OutboxMessage, UUID> {

    @Query(value = """
            SELECT * FROM outbox_messages
            WHERE status = :status
            ORDER BY created_at ASC
            LIMIT :limit
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<OutboxMessage> findAndLockPendingMessages(@Param("status") String status, @Param("limit") int limit);

    List<OutboxMessage> findByStatusOrderByCreatedAtAsc(OutboxStatus status, Pageable pageable);

    List<OutboxMessage> findByStatusOrderByCreatedAtAsc(OutboxStatus status);

    List<OutboxMessage> findByAggregateId(String aggregateId);

    long countByStatus(OutboxStatus status);
}
