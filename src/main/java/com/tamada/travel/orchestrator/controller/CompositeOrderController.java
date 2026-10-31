package com.tamada.travel.orchestrator.controller;

import com.tamada.travel.orchestrator.dto.CreateOrderRequest;
import com.tamada.travel.orchestrator.dto.OrderResponse;
import com.tamada.travel.orchestrator.dto.error.ErrorResponse;
import com.tamada.travel.orchestrator.service.CompositeOrderService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/orders")
@RequiredArgsConstructor
@Tag(name = "Composite Orders", description = "Управление составными заказами путешествий (Saga Orchestrator)")
public class CompositeOrderController {

    private final CompositeOrderService service;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(
            summary = "Создать новый составной заказ",
            description = "Создает заказ со статусом NEW и списком шагов бронирования (FLIGHT, HOTEL, INSURANCE, TRANSFER). Шаги получают статус PENDING."
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "201",
                    description = "Заказ успешно создан"
            ),
            @ApiResponse(
                    responseCode = "400",
                    description = "Ошибка валидации входных данных (пустой список, неизвестный тип или null)",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))
            )
    })
    public OrderResponse createOrder(
            @Valid @RequestBody CreateOrderRequest request) {
        return service.createOrder(request);
    }

    @GetMapping("/{id}")
    @Operation(
            summary = "Получить информацию о заказе по ID",
            description = "Возвращает текущее состояние заказа и всех входящих в него шагов бронирования."
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "Информация о заказе найдена"
            ),
            @ApiResponse(
                    responseCode = "404",
                    description = "Заказ с указанным ID не найден",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))
            )
    })
    public OrderResponse getOrder(
            @Parameter(description = "Уникальный идентификатор заказа", example = "01a10131-40db-780d-8f1c-9a4b0789cfd3")
            @PathVariable UUID id) {
        return service.getOrder(id);
    }

    @PostMapping("/{id}/start")
    @Operation(
            summary = "Запустить процесс оркестрации бронирования (Saga)",
            description = "Последовательно вызывает внешние сервисы бронирования. При сбое любого шага запускает обратную компенсацию уже выполненных броней."
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "Оркестрация завершена (заказ перешел в COMPLETED или CANCELLED)"
            ),
            @ApiResponse(
                    responseCode = "404",
                    description = "Заказ с указанным ID не найден",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))
            ),
            @ApiResponse(
                    responseCode = "409",
                    description = "Недопустимый статус заказа (запуск возможен только для заказов в статусе NEW)",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))
            )
    })
    public OrderResponse startOrder(
            @Parameter(description = "Уникальный идентификатор заказа", example = "01a10131-40db-780d-8f1c-9a4b0789cfd3")
            @PathVariable UUID id) {
        return service.startOrder(id);
    }

    @PostMapping("/{id}/cancel")
    @Operation(
            summary = "Отменить заказ и компенсировать выполненные брони",
            description = "Переводит заказ в отмену и вызывает эндпоинты /cancel для всех подтвержденных шагов."
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "Заказ успешно отменен (статус CANCELLED)"
            ),
            @ApiResponse(
                    responseCode = "404",
                    description = "Заказ с указанным ID не найден",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))
            ),
            @ApiResponse(
                    responseCode = "409",
                    description = "Заказ не может быть отменен из текущего статуса",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))
            )
    })
    public OrderResponse cancelOrder(
            @Parameter(description = "Уникальный идентификатор заказа", example = "01a10131-40db-780d-8f1c-9a4b0789cfd3")
            @PathVariable UUID id) {
        return service.cancelOrder(id);
    }
}
