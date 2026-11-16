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

### Запустить бронирование

```http
POST /api/v1/orders/{id}/start
```

Запуск синхронный: endpoint выполняет последовательные `/book` и при необходимости компенсацию.

### Отменить заказ

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

- circuit breaker;
- асинхронная Saga;
- Kafka;
- scheduler компенсаций;
- distributed transactions;
- отдельный workflow engine.
