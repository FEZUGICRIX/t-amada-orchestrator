package com.tamada.travel.orchestrator.observability;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tamada.travel.orchestrator.model.IdempotencyRecord;
import com.tamada.travel.orchestrator.model.IdempotencyStatus;
import com.tamada.travel.orchestrator.service.idempotency.IdempotencyAcquisition;
import com.tamada.travel.orchestrator.service.idempotency.IdempotencyService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class IdempotencyFilterTest {

    private IdempotencyService service;
    private IdempotencyFilter filter;
    private FilterChain filterChain;

    @BeforeEach
    void setUp() {
        service = mock(IdempotencyService.class);
        filter = new IdempotencyFilter(service, new ObjectMapper());
        filterChain = mock(FilterChain.class);
    }

    @Test
    void shouldPassThroughWhenNoIdempotencyKeyHeader() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/orders");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilterInternal(request, response, filterChain);

        verify(filterChain).doFilter(any(), any());
        verifyNoInteractions(service);
    }

    @Test
    void shouldPassThroughWhenMethodIsGet() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/orders");
        request.addHeader(IdempotencyFilter.HEADER_IDEMPOTENCY_KEY, "test-key");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilterInternal(request, response, filterChain);

        verify(filterChain).doFilter(any(), any());
        verifyNoInteractions(service);
    }

    @Test
    void shouldReturnReplayWhenKeyAlreadyCompleted() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/orders");
        request.addHeader(IdempotencyFilter.HEADER_IDEMPOTENCY_KEY, "key-123");
        request.setContent("{\"customerName\":\"Ivan\"}".getBytes());
        MockHttpServletResponse response = new MockHttpServletResponse();

        IdempotencyRecord record = new IdempotencyRecord("key-123", "/api/v1/orders", "hash", IdempotencyStatus.COMPLETED, Instant.now().plusSeconds(60));
        record.setResponseStatus(201);
        record.setResponseBody("{\"id\":\"order-123\"}");

        when(service.acquire(eq("key-123"), eq("/api/v1/orders"), anyString(), any()))
                .thenReturn(IdempotencyAcquisition.replay(record));

        filter.doFilterInternal(request, response, filterChain);

        assertEquals(201, response.getStatus());
        assertEquals("true", response.getHeader(IdempotencyFilter.HEADER_IDEMPOTENT_REPLAYED));
        assertEquals("{\"id\":\"order-123\"}", response.getContentAsString());
        verify(filterChain, never()).doFilter(any(), any());
    }

    @Test
    void shouldReturnConflictWhenKeyInProgress() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/orders");
        request.addHeader(IdempotencyFilter.HEADER_IDEMPOTENCY_KEY, "key-456");
        MockHttpServletResponse response = new MockHttpServletResponse();

        IdempotencyRecord record = new IdempotencyRecord("key-456", "/api/v1/orders", "hash", IdempotencyStatus.IN_PROGRESS, Instant.now().plusSeconds(60));
        when(service.acquire(eq("key-456"), eq("/api/v1/orders"), anyString(), any()))
                .thenReturn(IdempotencyAcquisition.conflict(record));

        filter.doFilterInternal(request, response, filterChain);

        assertEquals(409, response.getStatus());
        assertTrue(response.getContentAsString().contains("currently being processed"));
        verify(filterChain, never()).doFilter(any(), any());
    }

    @Test
    void shouldReturnUnprocessableEntityWhenPayloadMismatches() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/orders");
        request.addHeader(IdempotencyFilter.HEADER_IDEMPOTENCY_KEY, "key-789");
        MockHttpServletResponse response = new MockHttpServletResponse();

        IdempotencyRecord record = new IdempotencyRecord("key-789", "/api/v1/orders", "original", IdempotencyStatus.COMPLETED, Instant.now().plusSeconds(60));
        when(service.acquire(eq("key-789"), eq("/api/v1/orders"), anyString(), any()))
                .thenReturn(IdempotencyAcquisition.payloadMismatch(record));

        filter.doFilterInternal(request, response, filterChain);

        assertEquals(422, response.getStatus());
        assertTrue(response.getContentAsString().contains("different request payload"));
        verify(filterChain, never()).doFilter(any(), any());
    }
}
