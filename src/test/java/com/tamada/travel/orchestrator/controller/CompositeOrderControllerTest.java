package com.tamada.travel.orchestrator.controller;

import com.tamada.travel.orchestrator.dto.OrderResponse;
import com.tamada.travel.orchestrator.exception.InvalidBookingDataException;
import com.tamada.travel.orchestrator.exception.OrderNotFoundException;
import com.tamada.travel.orchestrator.model.OrderStatus;
import com.tamada.travel.orchestrator.service.CompositeOrderService;
import com.tamada.travel.orchestrator.exception.InvalidStateTransitionException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(CompositeOrderController.class)
class CompositeOrderControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private CompositeOrderService service;

    @Test
    void shouldCreateOrder() throws Exception {
        UUID id = UUID.randomUUID();

        OrderResponse response = new OrderResponse(
                id,
                OrderStatus.NEW,
                Instant.parse("2026-10-03T10:00:00Z"),
                Instant.parse("2026-10-03T10:00:00Z"),
                List.of()
        );

        when(service.createOrder(any()))
                .thenReturn(response);

        mockMvc.perform(
                        post("/api/v1/orders")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {
                                          "bookingTypes": [
                                            "FLIGHT",
                                            "HOTEL"
                                          ]
                                        }
                                        """)
                )
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(id.toString()))
                .andExpect(jsonPath("$.status").value("NEW"));
    }

    @Test
    void shouldRejectEmptyBookingTypes() throws Exception {
        mockMvc.perform(
                        post("/api/v1/orders")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {
                                          "bookingTypes": []
                                        }
                                        """)
                )
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("Bad Request"))
                .andExpect(
                        jsonPath("$.validationErrors.bookingTypes").exists()
                );
    }

    @Test
    void shouldRejectNullBookingTypes() throws Exception {
        mockMvc.perform(
                        post("/api/v1/orders")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {
                                          "bookingTypes": null
                                        }
                                        """)
                )
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));
    }

    @Test
    void shouldRejectUnknownBookingType() throws Exception {
        mockMvc.perform(
                        post("/api/v1/orders")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {
                                          "bookingTypes": [
                                            "PLANE"
                                          ]
                                        }
                                        """)
                )
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value("Invalid request body"));
    }

    @Test
    void shouldReturnBadRequestForDuplicates() throws Exception {
        when(service.createOrder(any()))
                .thenThrow(
                        new InvalidBookingDataException(
                                "Booking types must not contain duplicates"
                        )
                );

        mockMvc.perform(
                        post("/api/v1/orders")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {
                                          "bookingTypes": [
                                            "FLIGHT",
                                            "FLIGHT"
                                          ]
                                        }
                                        """)
                )
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.message")
                        .value("Booking types must not contain duplicates"));
    }

    @Test
    void shouldGetOrder() throws Exception {
        UUID id = UUID.randomUUID();

        OrderResponse response = new OrderResponse(
                id,
                OrderStatus.NEW,
                Instant.parse("2026-10-03T10:00:00Z"),
                Instant.parse("2026-10-03T10:00:00Z"),
                List.of()
        );

        when(service.getOrder(id))
                .thenReturn(response);

        mockMvc.perform(
                        get("/api/v1/orders/{id}", id)
                )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id.toString()))
                .andExpect(jsonPath("$.status").value("NEW"));
    }

    @Test
    void shouldReturn404WhenOrderNotFound() throws Exception {
        UUID id = UUID.randomUUID();

        when(service.getOrder(id))
                .thenThrow(new OrderNotFoundException(id));

        mockMvc.perform(
                        get("/api/v1/orders/{id}", id)
                )
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.error").value("Not Found"));
    }

    @Test
    void shouldRejectInvalidUuid() throws Exception {
        mockMvc.perform(
                        get("/api/v1/orders/not-a-uuid")
                )
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.message")
                        .value(
                                "Invalid value for parameter 'id'. Expected type: UUID"
                        ));
    }

    @Test
    void shouldStartOrder() throws Exception {
        UUID id = UUID.randomUUID();

        OrderResponse response = new OrderResponse(
                id,
                OrderStatus.PROCESSING,
                Instant.parse("2026-10-08T10:00:00Z"),
                Instant.parse("2026-10-08T10:00:00Z"),
                List.of()
        );

        when(service.startOrder(id))
                .thenReturn(response);

        mockMvc.perform(
                        post("/api/v1/orders/{id}/start", id)
                )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id.toString()))
                .andExpect(jsonPath("$.status").value("PROCESSING"));
    }

    @Test
    void shouldReturnConflictWhenOrderCannotBeStarted() throws Exception {
        UUID id = UUID.randomUUID();

        when(service.startOrder(id))
                .thenThrow(
                        new InvalidStateTransitionException(
                                "Cannot start order processing from status PROCESSING"
                        )
                );

        mockMvc.perform(
                        post("/api/v1/orders/{id}/start", id)
                )
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409));
    }
}