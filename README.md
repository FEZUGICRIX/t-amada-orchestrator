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
- `externalId` — ID созданной брони во внешнем сервисе.

## Saga flow

### Успешное бронирование

Шаги выполняются последовательно в порядке, указанном при создании заказа:

```text
FLIGHT -> HOTEL -> INSURANCE -> TRANSFER
```

После успешного `/book` шаг получает `CONFIRMED`, а внешний booking ID сохраняется в `externalId`. Когда подтверждены все шаги, заказ получает `COMPLETED`.

### Частичный отказ

Если `/book` завершился ошибкой:

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

### Ручная отмена

```http
POST /api/v1/orders/{id}/cancel
```

Для заказа в `PROCESSING` или `COMPLETED` отменяются только шаги `CONFIRMED`. Шаги `PENDING`, `FAILED` и `CANCELLED` не отправляются на внешний `/cancel`.

Автоматическая компенсация и ручная отмена используют один общий cancellation flow.

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
      "resourceId": "10000000-0000-0000-0000-000000000001"
    },
    {
      "type": "HOTEL",
      "resourceId": "20000000-0000-0000-0000-000000000002"
    }
  ]
}
```

Ограничения:

- от одного до четырёх шагов;
- `type` и `resourceId` обязательны;
- типы бронирования не должны повторяться.

### Получить заказ

```http
GET /api/v1/orders/{id}
```

### Запустить бронирование

```http
POST /api/v1/orders/{id}/start
```

Запуск синхронный: endpoint выполняет последовательные `/book` и при необходимости компенсацию.

### Отменить заказ

```http
POST /api/v1/orders/{id}/cancel
```

После успешной внешней отмены endpoint возвращает актуальный `OrderResponse` со статусом `CANCELLED`.

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

| HTTP status | Тип                    | Retryable    |
| ----------- | ---------------------- | ------------ |
| 400         | `VALIDATION_ERROR`     | нет          |
| 404         | `NOT_FOUND`            | нет          |
| 409         | `RESOURCE_UNAVAILABLE` | нет          |
| 500         | `SYSTEM_FAILURE`       | потенциально |

Retry в текущей версии не выполняется.

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

## Тесты

```bash
./mvnw test
```

Тесты не требуют реальных внешних сервисов или PostgreSQL:

- Spring context использует H2;
- HTTP-вызовы эмулируются через `MockRestServiceServer`;
- Saga integration tests проверяют цепочку Controller → Service → Orchestrator → RestBookingClient → mock HTTP service.

Покрыты успешное бронирование, частичные отказы, ошибки `409` и `500`, обратная компенсация, ручная отмена и ошибки внешнего `/cancel`.

Текущий результат:

```text
Tests run: 128, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

## Структура проекта

```text
src/main/java/com/tamada/travel/orchestrator
├── client       # внешние HTTP-клиенты и DTO
├── config       # конфигурация клиентов
├── controller   # REST API
├── dto          # API request/response
├── exception    # исключения и error handler
├── model        # CompositeOrder и BookingStep
├── repository   # Spring Data JPA
└── service      # application и orchestration logic
```

Flyway migrations находятся в `src/main/resources/db/migration`.


## Swagger / OpenAPI

Интерактивная документация API доступна после запуска приложения:
- **Swagger UI**: [http://localhost:8080/swagger-ui.html](http://localhost:8080/swagger-ui.html)
- **OpenAPI Schema**: [http://localhost:8080/v3/api-docs](http://localhost:8080/v3/api-docs)
## Ограничения

В текущей версии не реализованы:

- retry;
- circuit breaker;
- асинхронная Saga;
- Kafka;
- scheduler компенсаций;
- distributed transactions;
- отдельный workflow engine.
