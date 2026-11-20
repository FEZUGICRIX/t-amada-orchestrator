package com.tamada.travel.orchestrator.controller;

import com.tamada.travel.orchestrator.dto.CreateOrderRequest;
import com.tamada.travel.orchestrator.dto.OrderOperationHistoryResponse;
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
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping({"/api/v1/orders", "/orders"})
@RequiredArgsConstructor
@Tag(name = "Composite Orders", description = "Управление составными заказами путешествий (Saga Orchestrator)")
public class CompositeOrderController {

    private final CompositeOrderService service;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(
            summary = "Создать новый составной заказ",
            description = "Создает заказ со статусом NEW, списком шагов бронирования (рейс туда, рейс обратно, отель, страховка, трансфер), списком пассажиров и контактами заказчика. Шаги получают статус PENDING, событие сохраняется в Outbox."
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
            description = "Возвращает текущее состояние заказа и всех входящих в него шагов бронирования, а также подробные сведения об ошибках и историю операций."
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

    @GetMapping("/{id}/history")
    @Operation(
            summary = "Получить историю операций и неудачных попыток по заказу",
            description = "Возвращает хронологическую историю всех операций заказа (создание, бронирование, сбои поставщиков, компенсации и причины отмены остальных услуг)."
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "История операций заказа успешно получена"
            ),
            @ApiResponse(
                    responseCode = "404",
                    description = "Заказ с указанным ID не найден",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))
            )
    })
    public List<OrderOperationHistoryResponse> getOrderHistory(
            @Parameter(description = "Уникальный идентификатор заказа", example = "01a10131-40db-780d-8f1c-9a4b0789cfd3")
            @PathVariable UUID id) {
        return service.getOrderHistory(id);
    }

    @GetMapping(value = {"/{id}/events", "/{id}/stream", "/{id}/sse"}, produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(
            summary = "Подписка на события заказа в реальном времени (Server-Sent Events / SSE)",
            description = "Потоковая передача событий изменения статуса заказа, шагов бронирования, подробных ошибок поставщиков и причин компенсации остальных услуг."
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "Поток SSE-событий успешно установлен",
                    content = @Content(mediaType = MediaType.TEXT_EVENT_STREAM_VALUE)
            ),
            @ApiResponse(
                    responseCode = "404",
                    description = "Заказ с указанным ID не найден",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))
            )
    })
    public SseEmitter streamOrderEvents(
            @Parameter(description = "Уникальный идентификатор заказа", example = "01a10131-40db-780d-8f1c-9a4b0789cfd3")
            @PathVariable UUID id,
            @Parameter(description = "Автоматически запустить бронирование заказа при подписке, если статус заказа NEW")
            @RequestParam(required = false, defaultValue = "false") boolean start) {
        return service.subscribeToOrderEvents(id, start);
    }

    @PostMapping("/{id}/start")
    @Operation(
            summary = "Запустить процесс оркестрации бронирования (Saga)",
            description = "Последовательно вызывает внешние сервисы бронирования. При async=true возвращает HTTP 202 Accepted и выполняет сагу асинхронно в фоне, освобождая HTTP-поток и передавая события через SSE (/events). По умолчанию (async=false) выполняется синхронно (200 OK)."
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "Синхронная оркестрация завершена (заказ перешел в COMPLETED или CANCELLED)"
            ),
            @ApiResponse(
                    responseCode = "202",
                    description = "Асинхронная оркестрация принята в обработку (HTTP-поток освобожден, заказ в статусе PROCESSING)"
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
    public ResponseEntity<OrderResponse> startOrder(
            @Parameter(description = "Уникальный идентификатор заказа", example = "01a10131-40db-780d-8f1c-9a4b0789cfd3")
            @PathVariable UUID id,
            @Parameter(description = "Выполнить оркестрацию асинхронно и освободить HTTP-поток (202 Accepted)")
            @RequestParam(required = false, defaultValue = "false") boolean async) {
        if (async) {
            return ResponseEntity.status(HttpStatus.ACCEPTED).body(service.startOrderAsync(id));
        }
        return ResponseEntity.ok(service.startOrder(id));
    }

    @PostMapping(value = {"/{id}/start-async", "/{id}/async"})
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Operation(
            summary = "Асинхронно запустить бронирование заказа (неблокирующий API 202 Accepted)",
            description = "Немедленно возвращает HTTP 202 Accepted со статусом заказа PROCESSING. Оркестрация выполняется в фоновом пуле потоков, а события транслируются клиенту в реальном времени через SSE (/events)."
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "202",
                    description = "Асинхронная оркестрация принята в обработку (HTTP-поток освобожден)"
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
    public OrderResponse startOrderAsync(
            @Parameter(description = "Уникальный идентификатор заказа", example = "01a10131-40db-780d-8f1c-9a4b0789cfd3")
            @PathVariable UUID id) {
        return service.startOrderAsync(id);
    }

    @PostMapping("/{id}/cancel")
    @Operation(
            summary = "Отменить заказ и компенсировать выполненные брони",
            description = "Переводит заказ в отмену и вызывает эндпоинты /cancel для всех подтвержденных шагов с сохранением причин отмены в истории."
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
