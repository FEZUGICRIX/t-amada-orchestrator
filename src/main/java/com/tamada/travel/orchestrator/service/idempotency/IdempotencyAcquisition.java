package com.tamada.travel.orchestrator.service.idempotency;

import com.tamada.travel.orchestrator.model.IdempotencyRecord;

/**
 * Результат проверки и захвата ключа идемпотентности.
 */
public record IdempotencyAcquisition(
        Outcome outcome,
        IdempotencyRecord record
) {
    public enum Outcome {
        PROCEED,
        REPLAY,
        CONFLICT,
        PAYLOAD_MISMATCH
    }

    public static IdempotencyAcquisition proceed(IdempotencyRecord record) {
        return new IdempotencyAcquisition(Outcome.PROCEED, record);
    }

    public static IdempotencyAcquisition replay(IdempotencyRecord record) {
        return new IdempotencyAcquisition(Outcome.REPLAY, record);
    }

    public static IdempotencyAcquisition conflict(IdempotencyRecord record) {
        return new IdempotencyAcquisition(Outcome.CONFLICT, record);
    }

    public static IdempotencyAcquisition payloadMismatch(IdempotencyRecord record) {
        return new IdempotencyAcquisition(Outcome.PAYLOAD_MISMATCH, record);
    }
}
