# T-Amada Orchestrator

Spring Boot-сервис для управления составным заказом путешествия. Заказ может включать бронирование:

- авиабилета;
- отеля;
- страховки;
- трансфера.

Orchestrator последовательно вызывает внешние сервисы, сохраняет идентификаторы броней и компенсирует уже выполненные шаги при частичном отказе.

## Стек

- Java 21
- Spring Boot 4.1.1
- Spring Web MVC
- OpenAPI 3 / Swagger UI (springdoc-openapi)
- Spring Data JPA / Hibernate
- PostgreSQL 17
- Flyway
- Maven
- Docker Compose
- JUnit 5, Mockito, MockMvc, MockRestServiceServer

## Архитектура

```text
CompositeOrderController
        |
        v
CompositeOrderService
        |
        v
BookingOrchestrationService
        |
        +--> CompositeOrderRepository
        |
        +--> BookingClientRegistry
                  |
                  +--> FLIGHT RestBookingClient
                  +--> HOTEL RestBookingClient
                  +--> INSURANCE RestBookingClient
                  +--> TRANSFER RestBookingClient
```

`BookingClientRegistry` выбирает HTTP-клиент по `BookingType`. Orchestration service не содержит `switch` по URL внешних сервисов.

## Доменная модель

### CompositeOrder

Статусы заказа:

```text
NEW -> PROCESSING -> COMPLETED
                  -> FAILED -> CANCELLING -> CANCELLED

PROCESSING/COMPLETED -> CANCELLING -> CANCELLED
```

### BookingStep

Типы:

```text
FLIGHT
HOTEL
INSURANCE
TRANSFER
```

Статусы:

```text
PENDING -> PROCESSING -> CONFIRMED
                      -> FAILED

CONFIRMED -> CANCELLING -> CANCELLED
```

Шаг содержит:

- `resourceId` — идентификатор бронируемого ресурса;
- `externalId` — ID созданной брони во внешнем сервисе;
- `price` — стоимость услуги;
- `roomsCount` — количество выбранных номеров (для отеля);
- `rooms` — список номеров отеля со следующими параметрами для каждого номера:
  - `category` — категория номера (например, Люкс, Стандарт, Deluxe);
  - `tariff` — тариф проживания (например, С завтраком, Без питания, Невозвратный);
  - `price` — стоимость номера;
  - `stayConditions` — условия проживания (например, время заезда/выезда, условия отмены, включенное питание).

Если для отеля указаны параметры номеров, общая стоимость шага отеля рассчитывается как сумма стоимостей всех выбранных номеров.

### Стоимость и сервисный сбор

- `basePrice` — сумма стоимостей всех выбранных шагов бронирования;
- `serviceFee` — сервисный сбор в размере 0.67% от стоимости заказа (`basePrice * 0.0067`, округление `HALF_UP` до 2 знаков);
- `totalPrice` — полная итоговая стоимость заказа (`basePrice + serviceFee`).

## Saga flow

### Успешное бронирование

Шаги выполняются последовательно в порядке, указанном при создании заказа:

```text
FLIGHT -> HOTEL -> INSURANCE -> TRANSFER
```

После успешного `/book` шаг получает `CONFIRMED`, а внешний booking ID сохраняется в `externalId`. Когда подтверждены все шаги, заказ получает `COMPLETED`.

### Частичный отказ и полная компенсация (All-or-Nothing)

При отключенной частичной компенсации (`saga.compensation.partial-compensation-enabled: false`), если `/book` завершился ошибкой:

1. текущий шаг получает `FAILED`;
2. заказ получает `FAILED`;
3. следующие `PENDING` шаги не запускаются;
4. ранее подтверждённые шаги отменяются через `/cancel` в обратном порядке;
5. после успешной компенсации заказ получает `CANCELLED`.

Пример:

```text
FLIGHT      CONFIRMED
HOTEL       CONFIRMED
INSURANCE   FAILED
TRANSFER    PENDING

Компенсация: HOTEL -> FLIGHT
```

Если внешний `/cancel` завершился ошибкой, текущий шаг и заказ остаются в `CANCELLING`. Дальнейшая компенсация останавливается.

### Частичная компенсация и замена отеля (Partial Compensation & Hotel Replacement)

По умолчанию включена частичная компенсация (`saga.compensation.partial-compensation-enabled: true`), обеспечивающая сохранение выкупленных услуг при сбое бронирования отеля:

1. **Сохранение выкупленных авиабилетов**:
   - При сбое бронирования отеля (например, 409 Conflict / номер распродан) выкупленные авиабилеты (`FLIGHT`) **не отменяются** и сохраняют статус `CONFIRMED`.
   - Заказ переходит в статус `FAILED` (а не в терминальный `CANCELLED`), ожидая действий пользователя по выбору альтернативного отеля.
   - В историю операций и поток SSE отправляются детальные причины сбоя отеля и уведомление о сохранности билетов.
2. **Замена отеля в рамках того же заказа**:
   - Пользователь может выбрать альтернативный вариант проживания через API `POST /api/v1/orders/{id}/hotel/replace` (или `PUT /api/v1/orders/{id}/hotel`).
   - При замене отеля шаг получает новый `resourceId`, параметры номеров (`rooms`), статус сбрасывается в `PENDING`, пересчитывается базовая стоимость заказа и сервисный сбор.
   - Если старый отель уже был подтвержден (`CONFIRMED`), выполняется его внешняя отмена (`/cancel`), а авиабилеты остаются нетронутыми.
3. **Возобновление и до-оркестрация**:
   - Запуск оркестрации (`POST /api/v1/orders/{id}/start` или `POST /api/v1/orders/{id}/start-async`) разрешен из статусов `NEW`, `FAILED`, а также `COMPLETED` (при наличии шагов в статусе `PENDING`).
   - Уже выкупленные услуги повторно не бронируются; запрос на бронирование отправляется только на замененный отель и оставшиеся шаги.
   - После успешного бронирования всех услуг заказ переходит в статус `COMPLETED`.
4. **Гарантированная компенсация при явной отмене**:
   - Если пользователь решает не выбирать альтернативный отель и отменить весь заказ через `POST /api/v1/orders/{id}/cancel`, сохраненные авиабилеты отменяются во внешнем сервисе, и заказ переходит в `CANCELLED`.

### Ручная отмена

```http
POST /api/v1/orders/{id}/cancel
```

Для заказа в `PROCESSING` или `COMPLETED` отменяются только шаги `CONFIRMED`. Шаги `PENDING`, `FAILED` и `CANCELLED` не отправляются на внешний `/cancel`.

Автоматическая компенсация и ручная отмена используют один общий cancellation flow.

### Авто-восстановление зависших заказов (Saga Recovery)

Если сервис перезагрузился или упал во время бронирования, заказ может зависнуть в статусе `PROCESSING`.
Для предотвращения зависания реализован фоновый воркер `SagaRecoveryWorker`:

1. **Триггеры**: срабатывает при старте приложения (`ApplicationReadyEvent`) и периодически по таймеру (`@Scheduled`, настраиваемый интервал `saga.recovery.fixed-delay`).
2. **Поиск зависших заказов**: выбирает из базы данных заказы со статусом `PROCESSING`, которые висят без изменений дольше заданного порога (по умолчанию 2 минуты, `saga.recovery.stuck-threshold: 2m`).
3. **Безопасное возобновление**:
   - Уже выкупленные и подтвержденные услуги (`CONFIRMED`) пропускаются и не бронируются повторно.
   - Если шаг был прерван посреди выполнения в состоянии `PROCESSING`, он безопасно сбрасывается в `PENDING`.
   - Запросы на бронирование отправляются только на оставшиеся (`PENDING`) услуги.
4. **Завершение саги**:
   - При успехе всех оставшихся шагов заказ доводится до `COMPLETED` и публикуется Outbox-событие `ORDER_COMPLETED`.
   - При сбое любого шага запускается компенсация всех подтвержденных услуг в обратном порядке, заказ переходит в `CANCELLED` и публикуется Outbox-событие `ORDER_CANCELLED`.

Настройки воркера в `application.yml`:
```yaml
saga:
  recovery:
    enabled: true
    stuck-threshold: 2m
    fixed-delay: 60s
    initial-delay: 60s
```

### Гарантированная компенсация зависших отмен (Saga Compensation Scheduler)

Если внешний сервис был временно недоступен или оркестратор был прерван во время отмены, заказ может зависнуть в статусе `CANCELLING`.
Для обеспечения бизнес-надежности и гарантии доведения компенсаций до внешних поставщиков реализован фоновый воркер `SagaCompensationWorker`:

1. **Поиск зависших отмен**: периодически по расписанию (`@Scheduled`) и на старте приложения находит заказы со статусом `CANCELLING`, не обновлявшиеся дольше заданного порога (`saga.compensation.stuck-threshold: 2m`).
2. **Возобновление компенсации**:
   - Выбирает оставшиеся неподтвержденные к отмене шаги (`CONFIRMED` и `CANCELLING`) в обратном порядке.
   - Повторно вызывает `/cancel` внешних сервисов с сохранением причины компенсации в истории операций.
   - Шаги, уже перешедшие в `CANCELLED`, пропускаются.
3. **Финализация**:
   - После успешной отмены всех шагов заказ переходит в статус `CANCELLED`.
   - Сохраняется Outbox-событие `ORDER_CANCELLED`.
   - В поток SSE отправляется итоговое событие `order-cancelled`.

Настройки в `application.yml`:
```yaml
saga:
  compensation:
    enabled: true
    stuck-threshold: 2m
    fixed-delay: 60s
    initial-delay: 60s
```

### Защита от зависания потоков: Circuit Breaker (Resilience4j)

Для предотвращения исчерпания пула потоков приложения при падении внешних отелей, авиалиний, страховых и трансферных поставщиков вызовы защищены с помощью **Resilience4j Circuit Breaker**:

- **Fail Fast**: при превышении порога ошибок (`failure-rate-threshold: 50%`) предохранитель переходит в состояние `OPEN` и мгновенно отклоняет последующие вызовы с кодом `503 Service Unavailable`, не блокируя потоки на сетевых таймаутах.
- **Изоляция сбоев**: каждый тип внешнего сервиса (`FLIGHT`, `HOTEL`, `INSURANCE`, `TRANSFER`) имеет собственный экземпляр Circuit Breaker. Падение сервиса отелей не блокирует бронирование авиабилетов.
- **Умная фильтрация**: бизнес-ошибки (`400 VALIDATION_ERROR`, `404 NOT_FOUND`, `409 RESOURCE_UNAVAILABLE` — например, распроданный номер) игнорируются предохранителем и не приводят к открытию цепи. Учитываются только инфраструктурные сбои (`5xx`, таймауты, ошибки соединения).
- **Самовосстановление**: по истечении периода ожидания (`wait-duration-in-open-state: 10s`) цепь переходит в `HALF_OPEN` и пропускает тестовые запросы для проверки восстановления внешнего поставщика.

### Отправка Outbox-событий в Apache Kafka

Для масштабирования и интеграции с внешними потребителями (нотификации, биллинг, аналитика) без сильной связности (loose coupling) реализован паттерн **Transactional Outbox + Kafka Publisher**:

- **Гарантия доставки**: события (`ORDER_CREATED`, `ORDER_PROCESSING`, `ORDER_COMPLETED`, `ORDER_CANCELLED`) атомарно сохраняются в таблицу `outbox_messages` в рамках транзакции заказа.
- **Фоновый паблишер**: `OutboxEventPublisher` с заданной периодичностью считывает события в статусе `PENDING` и отправляет их в Kafka-топик `tamada.order-events`.
- **Строгий порядок (FIFO)**: в качестве ключа партиционирования Kafka используется `aggregateId` (ID заказа), что гарантирует строгую последовательность обработки всех событий одного заказа.
- **Заголовки**: сообщения обогащаются Kafka-заголовками `eventType`, `aggregateType`, `outboxId`.
- **Перевод в SENT / FAILED**: после успешной отправки в брокер Kafka сообщение помечается `status = SENT`. При ошибке отправки статус меняется на `FAILED`.

## API

Base URL:

```text
http://localhost:8080
```

### Создать заказ

```http
POST /api/v1/orders
Content-Type: application/json
```

```json
{
  "bookings": [
    {
      "type": "FLIGHT",
      "resourceId": "10000000-0000-0000-0000-000000000001",
      "price": 15000.00
    },
    {
      "type": "HOTEL",
      "resourceId": "20000000-0000-0000-0000-000000000002",
      "roomsCount": 2,
      "rooms": [
        {
          "category": "Люкс",
          "tariff": "С завтраком",
          "price": 12000.00,
          "stayConditions": "Завтрак включен, бесплатная отмена"
        },
        {
          "category": "Стандарт",
          "tariff": "Без питания",
          "price": 7000.00,
          "stayConditions": "Невозвратный"
        }
      ]
    }
  ]
}
```

Ограничения:

- от одного до пяти шагов (до двух перелётов, один отель, страховка, трансфер);
- бронирование авиабилета (`FLIGHT`) является обязательным для каждого составного заказа (минимум 1, максимум 2 для перелётов туда-обратно);
- `type` и `resourceId` обязательны;
- для одного отеля можно забронировать несколько номеров (`rooms`), сохранив параметры каждого номера отдельно (`category`, `tariff`, `price`, `stayConditions`).

### Получить заказ

```http
GET /api/v1/orders/{id}
```

### Запустить бронирование / Возобновить оркестрацию (Синхронно / Асинхронно)

Запуск возможен для заказов в статусе `NEW`, `FAILED` (после сбоя шага и замены услуги), а также `COMPLETED` (при наличии добавленных или замененных `PENDING` шагов).

**Синхронный запуск (200 OK):**
```http
POST /api/v1/orders/{id}/start
```
Endpoint выполняет последовательные `/book` в текущем HTTP-потоке для всех ожидающих (`PENDING`) шагов. Уже подтвержденные (`CONFIRMED`) услуги пропускаются. Возвращает результат после завершения саги (статус `COMPLETED`, `FAILED` или `CANCELLED`).

**Асинхронный запуск (202 Accepted — освобождение HTTP-потоков):**
```http
POST /api/v1/orders/{id}/start?async=true
POST /api/v1/orders/{id}/start-async
```
Неблокирующий вызов: возвращает HTTP `202 Accepted` со статусом заказа `PROCESSING`. Оркестрация передается выделенному пулу потоков (`ThreadPoolTaskExecutor`), а события в реальном времени транслируются через SSE (`/events`), освобождая HTTP-потоки сервлета.

### Заменить отель на альтернативный (POST / PUT)

Позволяет пользователю выбрать альтернативный отель в рамках того же заказа с сохранением уже выкупленных авиабилетов:

```http
POST /api/v1/orders/{id}/hotel/replace
PUT /api/v1/orders/{id}/hotel
Content-Type: application/json
```

```json
{
  "resourceId": "b1eebc99-9c0b-4ef8-bb6d-6bb9bd380a99",
  "price": 12500.00,
  "roomsCount": 1,
  "rooms": [
    {
      "category": "Делюкс",
      "tariff": "С завтраком",
      "price": 12500.00,
      "stayConditions": "Завтрак включен, бесплатная отмена"
    }
  ]
}
```

- Если предыдущий отель уже был подтвержден (`CONFIRMED`), выполняется его внешняя частичная компенсация (`/cancel`).
- Новый отель переходит в статус `PENDING`.
- Пересчитывается общая стоимость заказа (`basePrice`), сервисный сбор (`serviceFee`) и итоговая цена (`totalPrice`).
- После замены отеля оркестрацию можно возобновить вызовом `POST /api/v1/orders/{id}/start`.

### Модифицировать шаг бронирования (универсальный эндпоинт)

```http
PUT /api/v1/orders/{id}/steps/{stepId}
Content-Type: application/json
```

```json
{
  "type": "HOTEL",
  "resourceId": "b3eebc99-9c0b-4ef8-bb6d-6bb9bd380a77",
  "price": 16000.00
}
```

Универсальный эндпоинт для замены любого шага бронирования (отель, трансфер, страховка). Если шаг был подтвержден, запускается его частичная компенсация, статус переводится в `PENDING`, а стоимость пересчитывается.

### Отменить конкретный шаг бронирования

```http
POST /api/v1/orders/{id}/steps/{stepId}/cancel
```

Отменяет отдельную подтвержденную услугу через внешний `/cancel` с сохранением остальных забронированных услуг в заказе.

### Отменить заказ (полная отмена)

```http
POST /api/v1/orders/{id}/cancel
```

После успешной внешней отмены endpoint возвращает актуальный `OrderResponse` со статусом `CANCELLED` и сохраняет причины отмены всех услуг.

### История операций и сбоев

```http
GET /api/v1/orders/{id}/history
```

Возвращает хронологическую историю всех операций по заказу: создание, попытки бронирования, детальные ошибки внешних поставщиков, компенсационные отмены и пропуск оставшихся услуг.

### События в реальном времени (Server-Sent Events / SSE)

```http
GET /api/v1/orders/{id}/events
Accept: text/event-stream
```

Потоковая передача событий по заказу в реальном времени:
- `connected` — подтверждение подключения с текущим состоянием заказа;
- `order-status` — изменение статуса заказа (`PROCESSING`, `COMPLETED`, `CANCELLED`);
- `step-status` — переход шагов бронирования (`PROCESSING`, `CONFIRMED`);
- `step-failed` — сбой шага с подробностями ошибки от поставщика и понятным текстом для пользователя;
- `step-cancelled` — отмена/компенсация шага с указанием причины отмены;
- `order-completed` / `order-cancelled` — итоговые события жизненного цикла заказа.

Поддерживается параметр `?start=true` для автоматического запуска оркестрации при открытии SSE-потока.
Алиасы маршрута: `/api/v1/orders/{id}/stream`, `/api/v1/orders/{id}/sse`.

## Внешние сервисы

| Тип       | Base URL                | Book                     | Cancel                     |
| --------- | ----------------------- | ------------------------ | -------------------------- |
| FLIGHT    | `http://localhost:3001` | `/api/v1/flight/book`    | `/api/v1/flight/cancel`    |
| HOTEL     | `http://localhost:3002` | `/api/v1/hotel/book`     | `/api/v1/hotel/cancel`     |
| INSURANCE | `http://localhost:3003` | `/api/v1/insurance/book` | `/api/v1/insurance/cancel` |
| TRANSFER  | `http://localhost:3004` | `/api/v1/transfer/book`  | `/api/v1/transfer/cancel`  |

### Book request

```json
{
  "resourceId": "10000000-0000-0000-0000-000000000001",
  "bookingReference": "0199f000-0000-7000-8000-000000000002"
}
```

`bookingReference` равен внутреннему ID `BookingStep`.

### Cancel request

```json
{
  "bookingId": "30000000-0000-0000-0000-000000000003"
}
```

`bookingId` берётся из `BookingStep.externalId`. После ответа проверяются booking ID и `status == CANCELLED`.

## Обработка ошибок

Внешние сервисы возвращают ошибку в формате:

```json
{
  "success": false,
  "statusCode": 409,
  "message": "Resource is unavailable",
  "error": "Conflict",
  "timestamp": "2026-10-18T10:00:00Z",
  "path": "/api/v1/hotel/book"
}
```

Для validation error `message` также может быть массивом строк.

Классификация:

| HTTP status / Ошибка | Тип                    | Retryable |
| -------------------- | ---------------------- | --------- |
| 400                  | `VALIDATION_ERROR`     | нет       |
| 404                  | `NOT_FOUND`            | нет       |
| 409                  | `RESOURCE_UNAVAILABLE` | нет       |
| 500, 502, 503, 504   | `SYSTEM_FAILURE`       | да        |
| Таймаут / Сеть       | `SYSTEM_FAILURE`       | да        |

Для retryable-ошибок (`SYSTEM_FAILURE`, сетевые сбои, таймауты и HTTP 5xx) выполняется автоматическая переотправка с настраиваемой экспоненциальной задержкой (`services.retry.max-attempts`, `initial-delay`, `multiplier`, `max-delay`). Неповторяемые бизнес-ошибки (400, 404, 409) сразу приводят к прерыванию шага и запуску сага-компенсации.

### Сохранение детальной информации об ошибках и истории операций

При возникновении ошибки внешнего сервиса:
1. **Детальная информация о сбое**: для упавшего шага сохраняются HTTP-статус, тип ошибки (`errorCode`), техническое сообщение поставщика (`errorMessage`), структурированные детали (`errorDetails`: URL, заголовок ошибки, временная метка поставщика) и понятное сообщение для пользователя (`userMessage`).
2. **Понятные сообщения пользователю**: технические ошибки поставщиков преобразуются в дружелюбные сообщения (например, *"Выбранный номер в отеле недоступен для бронирования на указанные даты"*).
3. **Причины отмены остальных услуг**:
   - для компенсированных услуг фиксируется причина отмены (например, *"Отменено: компенсация бронирования авиабилета в связи со сбоем бронирования отеля"*);
   - для еще не начатых (пропущенных) услуг фиксируется причина отмены выполнения (например, *"Отменено (не выполнялось): бронирование трансфера отменено в связи со сбоем шага отеля"*).
4. **История операций (`order_operation_history`)**: все операции (попытки бронирования, сбои, компенсации, пропуск шагов и ручные отмены) хронологически сохраняются в БД и доступны через endpoint `GET /api/v1/orders/{id}/history`, а также возвращаются в `OrderResponse`.
5. **Server-Sent Events (SSE)**: все события сбоев, компенсаций и причин отмены транслируются в реальном времени подписчикам потока `GET /api/v1/orders/{id}/events`.

Ошибки API Orchestrator возвращаются в едином формате:

```json
{
  "timestamp": "2026-10-18T10:00:00Z",
  "status": 404,
  "error": "Not Found",
  "message": "Order not found: ...",
  "path": "/api/v1/orders/...",
  "validationErrors": null
}
```

## Запуск

### Требования

- JDK 21
- Docker с Docker Compose
- внешние сервисы на портах `3001–3004` для реального бронирования

### PostgreSQL

```bash
docker compose up -d
```

Параметры подключения:

```text
Host: localhost
Port: 5433
Database: tamada
User: tamada
Password: tamada
```

### Приложение

```bash
./mvnw spring-boot:run
```

Приложение запускается на `http://localhost:8080`.

Основная конфигурация находится в `src/main/resources/application.yml`.

## Наблюдаемость и мониторинг (Observability)

### Корреляция запросов и сквозной W3C Trace Context
- Входящий трафик: `CorrelationIdFilter` перехватывает заголовки `X-Correlation-Id` или `X-Request-Id`, помещает их в SLF4J `MDC` и возвращает в заголовке ответа `X-Correlation-Id`.
- Асинхронные потоки: `MdcTaskDecorator` переносит MDC контекст в рабочий пул `sagaTaskExecutor`.
- Исходящий трафик во внешние сервисы: `CorrelationIdClientInterceptor` автоматически обогащает все вызовы к внешним сервисам бронирования (Flight, Hotel, Insurance, Transfer) заголовками `X-Correlation-Id`, `X-Request-Id`, `X-Order-Id` и стандартным W3C `traceparent` (`00-{traceId}-{spanId}-01`).

### Метрики (Micrometer & Prometheus)
- Компонент `SagaMetrics` регистрирует детальные бизнес-метрики:
  - `orchestrator.saga.order.duration` (Timer SLA выполнения саги с тегом статуса `COMPLETED`, `FAILED`, `CANCELLED`);
  - `orchestrator.saga.orders.total` (Counter терминальных состояний саги);
  - `orchestrator.saga.steps.total` (Counter шагов бронирования по типам и статусам);
  - `orchestrator.booking.client.retries.total` (Counter повторных попыток вызовов);
  - `orchestrator.saga.compensations.total` (Counter компенсаций с тегом режима `PARTIAL` / `FULL`);
  - `orchestrator.outbox.published.total` и `orchestrator.outbox.dlq.total` (Counter опубликованных и направленных в DLQ событий).

### Health Indicators (Spring Boot Actuator)
- Подключен `spring-boot-starter-actuator` с кастомным `SagaOrchestratorHealthIndicator` (доступен по адресу `/actuator/health`).
- Индикатор отслеживает:
  - Состояние Circuit Breaker для каждого типа сервиса (`FLIGHT`, `HOTEL`, `INSURANCE`, `TRANSFER`). При переходе любого Circuit Breaker в состояние `OPEN` статус сервиса переходит в `DOWN`.
  - Размер очереди ожидающих сообщений Transactional Outbox. Если количество неотправленных сообщений превышает пороговое значение (по умолчанию 1000), индикатор сообщает о деградации `OUTBOX_BACKLOG_HIGH`.

## Отказоустойчивость и безопасность (Fault Tolerance)

- **Идемпотентность API (IETF Idempotency-Key Standard)**:
  - Для защиты от дублирования заказов при повторных отправках запросов реализован `IdempotencyFilter` и `IdempotencyService`.
  - Клиент передает заголовок `Idempotency-Key` при вызове мутирующих методов (`POST /api/v1/orders`).
  - При повторном запросе с тем же телом сервер моментально возвращает кэшированный ответ с заголовком `Idempotent-Replayed: true`.
  - При одновременных параллельных запросах возвращается `409 Conflict`.
  - При попытке повторного использования ключа с другим телом запроса возвращается `422 Unprocessable Content`.
  - Данные фиксируются в таблице `idempotency_records` (миграция `V9__create_idempotency_records_table.sql`).
- **Dead Letter Queue (DLQ) для Transactional Outbox**:
  - При постоянных сбоях доставки сообщений в основной топик Kafka (`tamada.order-events`) сообщения автоматически перенаправляются в очередь недоставленных сообщений (`tamada.order-events.DLQ`) со статусом `DEAD_LETTER` и служебными заголовками `x-original-topic`, `x-exception-message` и `x-failed-at`.
- **Exponential Backoff с Full Jitter**:
  - Механизм повторных вызовов `RetryingBookingClient` поддерживает рандомизацию задержки (`Full Jitter` в диапазоне `[0.5 * delay, delay]`), устраняя эффект лавины запросов («thundering herd problem») при восстановлении внешних систем. Настраивается через `services.retry.jitter-enabled: true`.
- **Circuit Breaker**:
  - Resilience4j защищает систему от каскадных сбоев при длительной недоступности внешних поставщиков.
- **Graceful Shutdown**:
  - Пул потоков асинхронного выполнения `sagaTaskExecutor` настроен с параметрами `waitForTasksToCompleteOnShutdown(true)` и `awaitTerminationSeconds(30)`, обеспечивая завершение активных операций бронирования при остановке приложения.
- **Изоляция блокировок (SagaLock Abstraction)**:
  - Выделен чистый интерфейс `SagaLock` (DIP), изолирующий логику взаимного исключения от бизнес-логики саги и поддерживающий легкую замену in-memory реализации на Redis/Advisory Locks.

## Тесты

```bash
./mvnw test
```

Тесты не требуют реальных внешних сервисов или PostgreSQL:

- Spring context использует H2;
- HTTP-вызовы эмулируются через `MockRestServiceServer`;
- Saga integration tests проверяют цепочку Controller → Service → Orchestrator → RestBookingClient → mock HTTP service.

Покрыты успешное бронирование, частичные отказы, ошибки `409` и `500`, обратная компенсация, ручная отмена, ошибки внешнего `/cancel`, параллельная обработка, работа Transactional Outbox, DLQ маршрутизация, Idempotency-Key, Correlation ID, Micrometer метрики и Health Indicators.

Текущий результат:

```text
Tests run: 288, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

## Структура проекта

```text
src/main/java/com/tamada/travel/orchestrator
├── client         # внешние HTTP-клиенты, DTO, Retrying, JitterStrategy и CircuitBreaker декораторы
├── config         # конфигурация клиентов, Kafka, Resilience4j, ThreadPoolTaskExecutor, Properties
├── controller     # REST API (синхронные и асинхронные эндпоинты 202 Accepted)
├── dto            # API request/response и Outbox payloads
├── exception      # иерархия доменных и клиентских исключений, GlobalExceptionHandler
├── model          # CompositeOrder (Aggregate Root), BookingStep, OutboxMessage, OrderOperationHistory
├── observability  # CorrelationIdFilter, MdcTaskDecorator, SagaOrchestratorHealthIndicator
├── repository     # Spring Data JPA репозитории
└── service        # orchestration, SSE, Outbox, StepErrorMapper, SagaRecovery/Compensation workers
```

Flyway migrations находятся в `src/main/resources/db/migration`.


## Swagger / OpenAPI

Интерактивная документация API доступна после запуска приложения:
- **Swagger UI**: [http://localhost:8080/swagger-ui.html](http://localhost:8080/swagger-ui.html)
- **OpenAPI Schema**: [http://localhost:8080/v3/api-docs](http://localhost:8080/v3/api-docs)
## Ограничения

В текущей версии не реализованы:

- distributed transactions (2PC / XA);
- отдельный workflow engine (Temporal / Camunda).
