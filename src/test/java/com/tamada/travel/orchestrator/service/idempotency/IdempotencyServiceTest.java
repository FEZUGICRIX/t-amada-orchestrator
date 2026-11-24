package com.tamada.travel.orchestrator.service.idempotency;

import com.tamada.travel.orchestrator.model.IdempotencyRecord;
import com.tamada.travel.orchestrator.model.IdempotencyStatus;
import com.tamada.travel.orchestrator.repository.IdempotencyRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class IdempotencyServiceTest {

    private IdempotencyRepository repository;
    private IdempotencyService service;

    @BeforeEach
    void setUp() {
        repository = mock(IdempotencyRepository.class);
        service = new IdempotencyService(repository);
    }

    @Test
    void shouldAcquireNewKeySuccessfully() {
        when(repository.findByKey("key-1")).thenReturn(Optional.empty());
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        IdempotencyAcquisition acquisition = service.acquire("key-1", "/api/v1/orders", "hash123", Duration.ofMinutes(2));

        assertEquals(IdempotencyAcquisition.Outcome.PROCEED, acquisition.outcome());
        assertEquals("key-1", acquisition.record().getKey());
        assertEquals(IdempotencyStatus.IN_PROGRESS, acquisition.record().getStatus());

        ArgumentCaptor<IdempotencyRecord> captor = ArgumentCaptor.forClass(IdempotencyRecord.class);
        verify(repository).save(captor.capture());
        assertEquals("hash123", captor.getValue().getRequestHash());
    }

    @Test
    void shouldReturnReplayWhenKeyAlreadyCompletedWithMatchingHash() {
        IdempotencyRecord record = new IdempotencyRecord("key-2", "/api/v1/orders", "hashMatch", IdempotencyStatus.COMPLETED, Instant.now().plusSeconds(60));
        record.setResponseStatus(201);
        record.setResponseBody("{\"id\":\"order-123\"}");
        when(repository.findByKey("key-2")).thenReturn(Optional.of(record));

        IdempotencyAcquisition acquisition = service.acquire("key-2", "/api/v1/orders", "hashMatch", Duration.ofMinutes(2));

        assertEquals(IdempotencyAcquisition.Outcome.REPLAY, acquisition.outcome());
        assertEquals(201, acquisition.record().getResponseStatus());
        assertEquals("{\"id\":\"order-123\"}", acquisition.record().getResponseBody());
        verify(repository, never()).save(any());
    }

    @Test
    void shouldReturnPayloadMismatchWhenHashDiffers() {
        IdempotencyRecord record = new IdempotencyRecord("key-3", "/api/v1/orders", "originalHash", IdempotencyStatus.COMPLETED, Instant.now().plusSeconds(60));
        when(repository.findByKey("key-3")).thenReturn(Optional.of(record));

        IdempotencyAcquisition acquisition = service.acquire("key-3", "/api/v1/orders", "differentHash", Duration.ofMinutes(2));

        assertEquals(IdempotencyAcquisition.Outcome.PAYLOAD_MISMATCH, acquisition.outcome());
        verify(repository, never()).save(any());
    }

    @Test
    void shouldReturnConflictWhenKeyIsCurrentlyInProgress() {
        IdempotencyRecord record = new IdempotencyRecord("key-4", "/api/v1/orders", "hash4", IdempotencyStatus.IN_PROGRESS, Instant.now().plusSeconds(60));
        when(repository.findByKey("key-4")).thenReturn(Optional.of(record));

        IdempotencyAcquisition acquisition = service.acquire("key-4", "/api/v1/orders", "hash4", Duration.ofMinutes(2));

        assertEquals(IdempotencyAcquisition.Outcome.CONFLICT, acquisition.outcome());
        verify(repository, never()).save(any());
    }

    @Test
    void shouldMarkCompletedAndFailed() {
        IdempotencyRecord record = new IdempotencyRecord("key-5", "/api/v1/orders", "hash5", IdempotencyStatus.IN_PROGRESS, Instant.now().plusSeconds(60));
        when(repository.findByKey("key-5")).thenReturn(Optional.of(record));

        service.markCompleted("key-5", 200, "{\"ok\":true}");
        assertEquals(IdempotencyStatus.COMPLETED, record.getStatus());
        assertEquals(200, record.getResponseStatus());
        assertEquals("{\"ok\":true}", record.getResponseBody());

        service.markFailed("key-5");
        assertEquals(IdempotencyStatus.FAILED, record.getStatus());
    }
}
