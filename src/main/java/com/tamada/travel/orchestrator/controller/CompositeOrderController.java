package com.tamada.travel.orchestrator.controller;

import com.tamada.travel.orchestrator.dto.CancelOrderRequest;
import com.tamada.travel.orchestrator.dto.CreateBookingStepRequest;
import com.tamada.travel.orchestrator.dto.CreateOrderRequest;
import com.tamada.travel.orchestrator.dto.OrderOperationHistoryResponse;
import com.tamada.travel.orchestrator.dto.OrderResponse;
import com.tamada.travel.orchestrator.dto.ReplaceHotelRequest;
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
            description = "Создает заказ со статусом NEW, списком шагов бронирования (рейс туда, рейс обратно, отель, страховка, трансфер), списком пассажиров и контактами заказчика. Шаги получают статус PENDING, событие сохраняется в Outbox. При start=true сразу инициирует оркестрацию бронирования (при Prefer: respond-async или async=true возвращает 202 Accepted, иначе выполняет сагу)."
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
    public ResponseEntity<OrderResponse> createOrder(
            @Valid @RequestBody CreateOrderRequest request,
            @Parameter(description = "Автоматически запустить оркестрацию бронирования сразу после создания заказа")
            @RequestParam(required = false, defaultValue = "false") boolean start,
            @Parameter(description = "Выполнить оркестрацию асинхронно (для start=true)")
            @RequestParam(required = false, defaultValue = "false") boolean async,
            @Parameter(description = "Стандартный HTTP-заголовок RFC 7240 (например, Prefer: respond-async)")
            @RequestHeader(value = "Prefer", required = false) String preferHeader) {
        OrderResponse created = service.createOrder(request);
        if (start) {
            boolean isAsync = async || (preferHeader != null && preferHeader.toLowerCase().contains("respond-async"));
            if (isAsync) {
                OrderResponse started = service.startOrderAsync(created.id());
                return ResponseEntity.status(HttpStatus.ACCEPTED).body(started);
            } else {
                OrderResponse started = service.startOrder(created.id());
                return ResponseEntity.status(HttpStatus.CREATED).body(started);
            }
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
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
            description = "Последовательно вызывает внешние сервисы бронирования. Запуск возможен из статуса NEW, а также FAILED (при замене отеля/до-оркестрации) или COMPLETED (при наличии невыполненных шагов). При async=true возвращает HTTP 202 Accepted и выполняет сагу асинхронно в фоне, освобождая HTTP-поток и передавая события через SSE (/events). По умолчанию (async=false) выполняется синхронно (200 OK)."
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "Синхронная оркестрация завершена (заказ перешел в COMPLETED, CANCELLED или FAILED)"
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
                    description = "Недопустимый статус заказа (запуск возможен для заказов в статусе NEW, FAILED или COMPLETED с ожидающими шагами)",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))
            )
    })
    public ResponseEntity<OrderResponse> startOrder(
            @Parameter(description = "Уникальный идентификатор заказа", example = "01a10131-40db-780d-8f1c-9a4b0789cfd3")
            @PathVariable UUID id,
            @Parameter(description = "Выполнить оркестрацию асинхронно и освободить HTTP-поток (202 Accepted)")
            @RequestParam(required = false, defaultValue = "false") boolean async,
            @Parameter(description = "Стандартный HTTP-заголовок RFC 7240 (например, Prefer: respond-async)")
            @RequestHeader(value = "Prefer", required = false) String preferHeader) {
        boolean isAsync = async || (preferHeader != null && preferHeader.toLowerCase().contains("respond-async"));
        if (isAsync) {
            return ResponseEntity.status(HttpStatus.ACCEPTED).body(service.startOrderAsync(id));
        }
        return ResponseEntity.ok(service.startOrder(id));
    }

    @PostMapping(value = {"/{id}/start-async", "/{id}/async"})
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Operation(
            summary = "Асинхронно запустить бронирование заказа (неблокирующий API 202 Accepted)",
            description = "Немедленно возвращает HTTP 202 Accepted со статусом заказа PROCESSING. Оркестрация выполняется в фоновом пуле потоков, а события транслируются клиенту в реальном времени через SSE (/events). Поддерживается запуск из статусов NEW, FAILED и COMPLETED (при наличии невыполненных шагов)."
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
                    description = "Недопустимый статус заказа (запуск возможен для заказов в статусе NEW, FAILED или COMPLETED с ожидающими шагами)",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))
            )
    })
    public OrderResponse startOrderAsync(
            @Parameter(description = "Уникальный идентификатор заказа", example = "01a10131-40db-780d-8f1c-9a4b0789cfd3")
            @PathVariable UUID id) {
        return service.startOrderAsync(id);
    }

    @PostMapping(value = {"/{id}/hotel/replace", "/{id}/replace-hotel", "/{id}/hotel"})
    @Operation(
            summary = "Заменить отель на альтернативный с сохранением выкупленных билетов",
            description = "Позволяет пользователю выбрать альтернативный отель в рамках того же заказа без отмены уже выкупленных авиабилетов. Если предыдущий отель уже был подтвержден (CONFIRMED), выполняется его частичная компенсация во внешнем сервисе. Новый отель получает статус PENDING, пересчитывается общая стоимость заказа и сервисный сбор."
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "Отель успешно заменен на альтернативный вариант"
            ),
            @ApiResponse(
                    responseCode = "400",
                    description = "Ошибка валидации данных отеля или заказ не содержит отель",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))
            ),
            @ApiResponse(
                    responseCode = "404",
                    description = "Заказ с указанным ID не найден",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))
            ),
            @ApiResponse(
                    responseCode = "409",
                    description = "Недопустимый статус заказа для модификации (заказ отменен)",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))
            )
    })
    public OrderResponse replaceHotel(
            @Parameter(description = "Уникальный идентификатор заказа", example = "01a10131-40db-780d-8f1c-9a4b0789cfd3")
            @PathVariable UUID id,
            @Valid @RequestBody ReplaceHotelRequest request) {
        return service.replaceHotel(id, request);
    }

    @PutMapping("/{id}/hotel")
    @Operation(
            summary = "Заменить отель на альтернативный (PUT)",
            description = "Алиас для замены отеля методом PUT с сохранением выкупленных билетов."
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "Отель успешно заменен на альтернативный вариант"
            ),
            @ApiResponse(
                    responseCode = "400",
                    description = "Ошибка валидации данных отеля или заказ не содержит отель",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))
            ),
            @ApiResponse(
                    responseCode = "404",
                    description = "Заказ с указанным ID не найден",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))
            ),
            @ApiResponse(
                    responseCode = "409",
                    description = "Недопустимый статус заказа для модификации (заказ отменен)",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))
            )
    })
    public OrderResponse replaceHotelPut(
            @Parameter(description = "Уникальный идентификатор заказа", example = "01a10131-40db-780d-8f1c-9a4b0789cfd3")
            @PathVariable UUID id,
            @Valid @RequestBody ReplaceHotelRequest request) {
        return service.replaceHotel(id, request);
    }

    @PutMapping("/{id}/steps/{stepId}")
    @Operation(
            summary = "Модифицировать шаг бронирования (универсальный эндпоинт)",
            description = "Универсальный эндпоинт для замены/модификации любого шага бронирования (отель, трансфер, страховка). Если шаг был подтвержден (CONFIRMED), выполняется его частичная компенсация. Шаг переходит в статус PENDING, а стоимость заказа пересчитывается."
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "Шаг бронирования успешно модифицирован"
            ),
            @ApiResponse(
                    responseCode = "400",
                    description = "Ошибка валидации данных шага",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))
            ),
            @ApiResponse(
                    responseCode = "404",
                    description = "Заказ или шаг бронирования не найден",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))
            ),
            @ApiResponse(
                    responseCode = "409",
                    description = "Недопустимый статус заказа для модификации",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))
            )
    })
    public OrderResponse replaceStep(
            @Parameter(description = "Уникальный идентификатор заказа", example = "01a10131-40db-780d-8f1c-9a4b0789cfd3")
            @PathVariable UUID id,
            @Parameter(description = "Идентификатор шага бронирования", example = "0199f000-0000-7000-8000-000000000002")
            @PathVariable UUID stepId,
            @Valid @RequestBody CreateBookingStepRequest request) {
        return service.replaceStep(id, stepId, request);
    }

    @PutMapping(value = {"/{id}/steps", "/{id}/step"})
    @Operation(
            summary = "Универсальный эндпоинт модификации любого шага бронирования",
            description = "Позволяет заменить или модифицировать любой шаг бронирования (отель, трансфер, страховка, рейс). Если шаг не указан через stepId, он определяется автоматически по типу услуги из тела запроса."
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "Шаг бронирования успешно модифицирован"
            ),
            @ApiResponse(
                    responseCode = "400",
                    description = "Ошибка валидации данных шага",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))
            ),
            @ApiResponse(
                    responseCode = "404",
                    description = "Заказ или шаг бронирования не найден",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))
            ),
            @ApiResponse(
                    responseCode = "409",
                    description = "Недопустимый статус заказа для модификации",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))
            )
    })
    public OrderResponse modifyStepUniversal(
            @Parameter(description = "Уникальный идентификатор заказа", example = "01a10131-40db-780d-8f1c-9a4b0789cfd3")
            @PathVariable UUID id,
            @Parameter(description = "Идентификатор шага бронирования (опционально, если указан тип в теле)", example = "0199f000-0000-7000-8000-000000000002")
            @RequestParam(required = false) UUID stepId,
            @Valid @RequestBody CreateBookingStepRequest request) {
        return service.modifyStep(id, stepId, request.type(), request);
    }

    @PostMapping("/{id}/steps/{stepId}/cancel")
    @Operation(
            summary = "Частичная отмена шага бронирования",
            description = "Выполняет частичную компенсацию (отмену) отдельного шага бронирования без отмены всего составного заказа."
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "Шаг успешно отменен"
            ),
            @ApiResponse(
                    responseCode = "404",
                    description = "Заказ или шаг бронирования не найден",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))
            ),
            @ApiResponse(
                    responseCode = "409",
                    description = "Недопустимый статус заказа или шага для отмены",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))
            )
    })
    public OrderResponse cancelStep(
            @Parameter(description = "Уникальный идентификатор заказа", example = "01a10131-40db-780d-8f1c-9a4b0789cfd3")
            @PathVariable UUID id,
            @Parameter(description = "Идентификатор шага бронирования", example = "0199f000-0000-7000-8000-000000000002")
            @PathVariable UUID stepId) {
        return service.cancelStep(id, stepId);
    }

    @PostMapping("/{id}/cancel")
    @Operation(
            summary = "Отменить заказ или отдельный шаг (универсальный эндпоинт отмены)",
            description = "Если в теле запроса передан stepId, выполняется частичная отмена конкретного шага без отмены всего заказа. Если тело не передано или stepId отсутствует, отменяется весь составной заказ с компенсацией выполненных броней."
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "Заказ или шаг успешно отменен"
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
            @PathVariable UUID id,
            @RequestBody(required = false) CancelOrderRequest cancelRequest) {
        if (cancelRequest != null && cancelRequest.stepId() != null) {
            return service.cancelStep(id, cancelRequest.stepId());
        }
        return service.cancelOrder(id);
    }

    @DeleteMapping("/{id}")
    @Operation(
            summary = "Отменить заказ или шаг через HTTP DELETE (REST-стандарт)",
            description = "Канонический HTTP DELETE эндпоинт для отмены всего заказа или конкретного шага (через параметр ?stepId=...)."
    )
    public OrderResponse deleteOrder(
            @Parameter(description = "Уникальный идентификатор заказа", example = "01a10131-40db-780d-8f1c-9a4b0789cfd3")
            @PathVariable UUID id,
            @Parameter(description = "Идентификатор шага (опционально, если передан - отменяется только он)")
            @RequestParam(required = false) UUID stepId) {
        if (stepId != null) {
            return service.cancelStep(id, stepId);
        }
        return service.cancelOrder(id);
    }

    @DeleteMapping("/{id}/steps/{stepId}")
    @Operation(
            summary = "Отменить шаг бронирования через HTTP DELETE",
            description = "Канонический HTTP DELETE эндпоинт для частичной отмены отдельного шага бронирования."
    )
    public OrderResponse deleteStep(
            @Parameter(description = "Уникальный идентификатор заказа", example = "01a10131-40db-780d-8f1c-9a4b0789cfd3")
            @PathVariable UUID id,
            @Parameter(description = "Идентификатор шага бронирования", example = "0199f000-0000-7000-8000-000000000002")
            @PathVariable UUID stepId) {
        return service.cancelStep(id, stepId);
    }
}
