package com.tamada.travel.orchestrator.controller;

import com.tamada.travel.orchestrator.dto.CreateOrderRequest;
import com.tamada.travel.orchestrator.dto.OrderResponse;
import com.tamada.travel.orchestrator.service.CompositeOrderService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/orders")
@RequiredArgsConstructor
public class CompositeOrderController {

    private final CompositeOrderService service;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public OrderResponse createOrder(
            @Valid @RequestBody CreateOrderRequest request
    ) {
        return service.createOrder(request);
    }

    @GetMapping("/{id}")
    public OrderResponse getOrder(
            @PathVariable UUID id
    ) {
        return service.getOrder(id);
    }

    @PostMapping("/{id}/start")
    public OrderResponse startOrder(@PathVariable UUID id) {
        return service.startOrder(id);
    }
}