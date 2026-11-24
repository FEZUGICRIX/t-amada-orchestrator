package com.tamada.travel.orchestrator.observability;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpResponse;

import java.io.IOException;
import java.net.URI;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class CorrelationIdClientInterceptorTest {

    private CorrelationIdClientInterceptor interceptor;
    private ClientHttpRequestExecution execution;
    private ClientHttpResponse response;

    @BeforeEach
    void setUp() {
        interceptor = new CorrelationIdClientInterceptor();
        execution = mock(ClientHttpRequestExecution.class);
        response = mock(ClientHttpResponse.class);
        CorrelationIdContext.clear();
    }

    @AfterEach
    void tearDown() {
        CorrelationIdContext.clear();
    }

    @Test
    void shouldInjectExistingCorrelationIdAndOrderIdIntoHeaders() throws IOException {
        String testCorrId = "test-corr-" + UUID.randomUUID();
        UUID testOrderId = UUID.randomUUID();
        CorrelationIdContext.setCorrelationId(testCorrId);
        CorrelationIdContext.setOrderId(testOrderId);

        HttpHeaders headers = new HttpHeaders();
        HttpRequest request = mock(HttpRequest.class);
        when(request.getHeaders()).thenReturn(headers);
        when(execution.execute(any(), any())).thenReturn(response);

        interceptor.intercept(request, new byte[0], execution);

        assertEquals(testCorrId, headers.getFirst(CorrelationIdContext.HEADER_CORRELATION_ID));
        assertEquals(testCorrId, headers.getFirst(CorrelationIdContext.HEADER_REQUEST_ID));
        assertEquals(testOrderId.toString(), headers.getFirst(CorrelationIdContext.HEADER_ORDER_ID));

        String traceparent = headers.getFirst(CorrelationIdClientInterceptor.HEADER_TRACEPARENT);
        assertNotNull(traceparent);
        assertTrue(traceparent.startsWith("00-"));
        assertTrue(traceparent.endsWith("-01"));
    }

    @Test
    void shouldGenerateCorrelationIdWhenNoneInContext() throws IOException {
        HttpHeaders headers = new HttpHeaders();
        HttpRequest request = mock(HttpRequest.class);
        when(request.getHeaders()).thenReturn(headers);
        when(execution.execute(any(), any())).thenReturn(response);

        interceptor.intercept(request, new byte[0], execution);

        String corrId = headers.getFirst(CorrelationIdContext.HEADER_CORRELATION_ID);
        assertNotNull(corrId);
        assertFalse(corrId.isBlank());
        assertEquals(corrId, headers.getFirst(CorrelationIdContext.HEADER_REQUEST_ID));
        assertNull(headers.getFirst(CorrelationIdContext.HEADER_ORDER_ID));
    }
}
