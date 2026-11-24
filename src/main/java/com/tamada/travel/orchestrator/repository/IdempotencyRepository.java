package com.tamada.travel.orchestrator.repository;

import com.tamada.travel.orchestrator.model.IdempotencyRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;

public interface IdempotencyRepository extends JpaRepository<IdempotencyRecord, String> {

    Optional<IdempotencyRecord> findByKey(String key);

    @Modifying
    @Query("DELETE FROM IdempotencyRecord r WHERE r.lockedUntil < :now AND r.status != 'COMPLETED'")
    int cleanupExpiredLocks(@Param("now") Instant now);
}
