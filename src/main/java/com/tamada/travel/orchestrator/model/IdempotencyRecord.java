package com.tamada.travel.orchestrator.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * Запись идемпотентности входящих API-запросов (IETF Idempotency-Key draft).
 * Позволяет безопасно повторять запросы создания и изменения заказов без риска дублирования.
 */
@Entity
@Table(name = "idempotency_records")
@Getter
@Setter
@NoArgsConstructor
public class IdempotencyRecord {

    @Id
    @Column(name = "idempotency_key", nullable = false, length = 128)
    private String key;

    @Column(name = "request_path", nullable = false, length = 255)
    private String requestPath;

    @Column(name = "request_hash", nullable = false, length = 64)
    private String requestHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private IdempotencyStatus status;

    @Column(name = "response_status")
    private Integer responseStatus;

    @Column(name = "response_body", columnDefinition = "TEXT")
    private String responseBody;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "locked_until", nullable = false)
    private Instant lockedUntil;

    public IdempotencyRecord(
            String key,
            String requestPath,
            String requestHash,
            IdempotencyStatus status,
            Instant lockedUntil
    ) {
        this.key = key;
        this.requestPath = requestPath;
        this.requestHash = requestHash;
        this.status = status;
        this.lockedUntil = lockedUntil;
        this.createdAt = Instant.now();
    }

    public boolean isExpired(Instant now) {
        return lockedUntil != null && lockedUntil.isBefore(now);
    }
}
