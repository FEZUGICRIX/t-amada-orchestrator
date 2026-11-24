package com.tamada.travel.orchestrator.service.idempotency;

import com.tamada.travel.orchestrator.model.IdempotencyRecord;
import com.tamada.travel.orchestrator.model.IdempotencyStatus;
import com.tamada.travel.orchestrator.repository.IdempotencyRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

@Service
@Slf4j
public class IdempotencyService {

    private final IdempotencyRepository repository;

    public IdempotencyService(IdempotencyRepository repository) {
        this.repository = Objects.requireNonNull(repository, "repository must not be null");
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public IdempotencyAcquisition acquire(
            String key,
            String requestPath,
            String payloadHash,
            Duration lockDuration
    ) {
        Instant now = Instant.now();
        Instant lockedUntil = now.plus(lockDuration != null ? lockDuration : Duration.ofMinutes(2));

        Optional<IdempotencyRecord> existingOpt = repository.findByKey(key);
        if (existingOpt.isPresent()) {
            IdempotencyRecord existing = existingOpt.get();

            if (existing.getStatus() == IdempotencyStatus.COMPLETED) {
                if (Objects.equals(existing.getRequestHash(), payloadHash)) {
                    log.info("Idempotency replay detected for key={} path={}", key, requestPath);
                    return IdempotencyAcquisition.replay(existing);
                } else {
                    log.warn("Idempotency payload mismatch for key={} path={}", key, requestPath);
                    return IdempotencyAcquisition.payloadMismatch(existing);
                }
            }

            if (existing.getStatus() == IdempotencyStatus.IN_PROGRESS) {
                if (!existing.isExpired(now)) {
                    log.warn("Idempotency conflict: key={} is currently in progress", key);
                    return IdempotencyAcquisition.conflict(existing);
                }
                log.info("Idempotency lock expired for key={}, taking over lock", key);
            }

            // Retry for failed or expired lock
            existing.setRequestPath(requestPath);
            existing.setRequestHash(payloadHash);
            existing.setStatus(IdempotencyStatus.IN_PROGRESS);
            existing.setLockedUntil(lockedUntil);
            existing.setResponseBody(null);
            existing.setResponseStatus(null);
            IdempotencyRecord updated = repository.save(existing);
            return IdempotencyAcquisition.proceed(updated);
        }

        try {
            IdempotencyRecord newRecord = new IdempotencyRecord(
                    key,
                    requestPath,
                    payloadHash,
                    IdempotencyStatus.IN_PROGRESS,
                    lockedUntil
            );
            IdempotencyRecord saved = repository.save(newRecord);
            return IdempotencyAcquisition.proceed(saved);
        } catch (org.springframework.dao.DataIntegrityViolationException ex) {
            log.warn("Concurrent idempotency insert race for key={}, falling back to in-progress conflict or replay", key);
            return repository.findByKey(key)
                    .map(rec -> rec.getStatus() == IdempotencyStatus.COMPLETED
                            ? (Objects.equals(rec.getRequestHash(), payloadHash)
                                    ? IdempotencyAcquisition.replay(rec)
                                    : IdempotencyAcquisition.payloadMismatch(rec))
                            : IdempotencyAcquisition.conflict(rec))
                    .orElseGet(() -> IdempotencyAcquisition.conflict(null));
        }
    }

    @Transactional
    public void markCompleted(String key, int responseStatus, String responseBody) {
        repository.findByKey(key).ifPresent(record -> {
            record.setStatus(IdempotencyStatus.COMPLETED);
            record.setResponseStatus(responseStatus);
            record.setResponseBody(responseBody);
            repository.save(record);
            log.debug("Marked idempotency key={} as COMPLETED with status={}", key, responseStatus);
        });
    }

    @Transactional
    public void markFailed(String key) {
        repository.findByKey(key).ifPresent(record -> {
            record.setStatus(IdempotencyStatus.FAILED);
            repository.save(record);
            log.debug("Marked idempotency key={} as FAILED", key);
        });
    }

    @Scheduled(fixedDelay = 60000, initialDelay = 30000)
    @Transactional
    public void cleanupExpiredLocks() {
        int cleaned = repository.cleanupExpiredLocks(Instant.now());
        if (cleaned > 0) {
            log.debug("Cleaned up {} expired idempotency locks", cleaned);
        }
    }
}
