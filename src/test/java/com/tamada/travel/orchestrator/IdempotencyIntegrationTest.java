package com.tamada.travel.orchestrator;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tamada.travel.orchestrator.dto.CreateBookingStepRequest;
import com.tamada.travel.orchestrator.dto.CreateOrderRequest;
import com.tamada.travel.orchestrator.model.BookingType;
import com.tamada.travel.orchestrator.observability.IdempotencyFilter;
import com.tamada.travel.orchestrator.repository.CompositeOrderRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class IdempotencyIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private CompositeOrderRepository orderRepository;

    @Test
    void shouldHandleIdempotentOrderCreationAndReplay() throws Exception {
        String idempotencyKey = "order-create-" + UUID.randomUUID();

        CreateOrderRequest request = new CreateOrderRequest(
                List.of(new CreateBookingStepRequest(
                        BookingType.FLIGHT,
                        UUID.randomUUID(),
                        new BigDecimal("15000.00")
                ))
        );

        String requestJson = objectMapper.writeValueAsString(request);

        // 1. First execution -> 201 Created
        MvcResult firstResult = mockMvc.perform(post("/api/v1/orders")
                        .header(IdempotencyFilter.HEADER_IDEMPOTENCY_KEY, idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andReturn();

        String firstResponseContent = firstResult.getResponse().getContentAsString();
        String createdOrderId = objectMapper.readTree(firstResponseContent).get("id").asText();

        // 2. Second execution with identical payload and key -> 201 Replay
        mockMvc.perform(post("/api/v1/orders")
                        .header(IdempotencyFilter.HEADER_IDEMPOTENCY_KEY, idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson))
                .andExpect(status().isCreated())
                .andExpect(header().string(IdempotencyFilter.HEADER_IDEMPOTENT_REPLAYED, "true"))
                .andExpect(jsonPath("$.id").value(createdOrderId));

        // 3. Third execution with same key but DIFFERENT payload -> 422 Unprocessable Content
        CreateOrderRequest differentRequest = new CreateOrderRequest(
                List.of(new CreateBookingStepRequest(
                        BookingType.HOTEL,
                        UUID.randomUUID(),
                        new BigDecimal("50000.00")
                ))
        );

        mockMvc.perform(post("/api/v1/orders")
                        .header(IdempotencyFilter.HEADER_IDEMPOTENCY_KEY, idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(differentRequest)))
                .andExpect(status().is(422));

        // 4. Verify in DB: only one order was created with this ID
        assertEquals(1, orderRepository.findAll().stream()
                .filter(o -> o.getId().toString().equals(createdOrderId))
                .count());
    }
}
