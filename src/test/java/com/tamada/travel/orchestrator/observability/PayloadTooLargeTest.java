package com.tamada.travel.orchestrator.observability;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tamada.travel.orchestrator.service.idempotency.IdempotencyService;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class PayloadTooLargeTest {

    @Mock
    private IdempotencyService idempotencyService;

    @Mock
    private FilterChain filterChain;

    private IdempotencyFilter filter;

    @BeforeEach
    void setUp() {
        filter = new IdempotencyFilter(idempotencyService, new ObjectMapper());
    }

    @Test
    void shouldReturn413WhenContentLengthExceeds2Megabytes() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/orders");
        request.addHeader(IdempotencyFilter.HEADER_IDEMPOTENCY_KEY, "test-key-overflow");
        // 2 MB + 1 byte
        byte[] oversizedPayload = new byte[2 * 1024 * 1024 + 1];
        request.setContent(oversizedPayload);

        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilterInternal(request, response, filterChain);

        assertEquals(413, response.getStatus());
        assertTrue(response.getContentAsString().contains("maximum allowed size"));
        verifyNoInteractions(filterChain);
        verifyNoInteractions(idempotencyService);
    }
}
