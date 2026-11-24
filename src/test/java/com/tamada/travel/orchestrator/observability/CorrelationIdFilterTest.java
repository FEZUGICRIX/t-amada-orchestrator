package com.tamada.travel.orchestrator.observability;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class CorrelationIdFilterTest {

    private final CorrelationIdFilter filter = new CorrelationIdFilter();

    @AfterEach
    void tearDown() {
        CorrelationIdContext.clear();
    }

    @Test
    void shouldExtractExistingCorrelationIdHeader() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(CorrelationIdContext.HEADER_CORRELATION_ID, "custom-corr-123");
        MockHttpServletResponse response = new MockHttpServletResponse();

        AtomicReference<String> mdcCorrelationIdInFilter = new AtomicReference<>();

        FilterChain chain = (req, res) -> mdcCorrelationIdInFilter.set(CorrelationIdContext.getCorrelationId());

        filter.doFilterInternal(request, response, chain);

        assertEquals("custom-corr-123", mdcCorrelationIdInFilter.get());
        assertEquals("custom-corr-123", response.getHeader(CorrelationIdContext.HEADER_CORRELATION_ID));
        assertNull(CorrelationIdContext.getCorrelationId(), "MDC must be cleared after filter completion");
    }

    @Test
    void shouldGenerateCorrelationIdWhenHeaderMissing() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        AtomicReference<String> mdcCorrelationIdInFilter = new AtomicReference<>();

        FilterChain chain = (req, res) -> mdcCorrelationIdInFilter.set(CorrelationIdContext.getCorrelationId());

        filter.doFilterInternal(request, response, chain);

        assertNotNull(mdcCorrelationIdInFilter.get());
        assertDoesNotThrow(() -> UUID.fromString(mdcCorrelationIdInFilter.get()));
        assertEquals(mdcCorrelationIdInFilter.get(), response.getHeader(CorrelationIdContext.HEADER_CORRELATION_ID));
        assertNull(CorrelationIdContext.getCorrelationId());
    }

    @Test
    void shouldPropagateMdcAcrossTaskDecorator() {
        MdcTaskDecorator decorator = new MdcTaskDecorator();
        CorrelationIdContext.setCorrelationId("test-trace-777");
        UUID orderId = UUID.randomUUID();
        CorrelationIdContext.setOrderId(orderId);

        AtomicReference<String> asyncCorrId = new AtomicReference<>();
        AtomicReference<String> asyncOrderId = new AtomicReference<>();

        Runnable task = decorator.decorate(() -> {
            asyncCorrId.set(CorrelationIdContext.getCorrelationId());
            asyncOrderId.set(org.slf4j.MDC.get(CorrelationIdContext.MDC_ORDER_ID));
        });

        // Run task in another thread
        Thread thread = new Thread(task);
        thread.start();
        try {
            thread.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        assertEquals("test-trace-777", asyncCorrId.get());
        assertEquals(orderId.toString(), asyncOrderId.get());
    }
}
