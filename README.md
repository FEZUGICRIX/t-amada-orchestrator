# T-Amada Orchestrator

Backend-сервис для работы с составными заказами путешествий.

Составной заказ может включать несколько шагов бронирования:

- авиабилеты
- отель
- страховка
- трансфер

## Стек

- Java 21
- Spring Boot 4.1.1
- Spring Web MVC
- Spring Data JPA
- Hibernate
- PostgreSQL
- Flyway
- Maven
- Docker Compose
- JUnit
- Mockito

## Структура проекта

```text
src/main/java/com/tamada/travel/orchestrator
├── config
├── controller
├── dto
├── exception
├── model
├── repository
├── service
└── OrchestratorApplication.java
```

## Модель

### CompositeOrder

Составной заказ.

Статусы:

```text
NEW
PROCESSING
COMPLETED
FAILED
CANCELLING
CANCELLED
```

### BookingStep

Отдельный шаг бронирования.

Типы:

```text
FLIGHT
HOTEL
INSURANCE
TRANSFER
```

Статусы:

```text
PENDING
PROCESSING
CONFIRMED
FAILED
CANCELLING
CANCELLED
```

Связь:

```text
CompositeOrder 1 ---- N BookingStep
```

Для идентификаторов используется UUIDv7.

## API

### Создать заказ

```http
POST /api/v1/orders
Content-Type: application/json
```

Пример:

```json
{
  "bookingTypes": [
    "FLIGHT",
    "HOTEL",
    "INSURANCE",
    "TRANSFER"
  ]
}
```

### Получить заказ

```http
GET /api/v1/orders/{id}
```

## Валидация и ошибки

Реализована проверка входных данных:

- пустого списка шагов
- `null` значений
- повторяющихся типов бронирования
- некорректных значений enum
- некорректного UUID

Ошибки API возвращаются в едином формате:

```json
{
  "timestamp": "2026-10-03T10:00:00Z",
  "status": 404,
  "error": "Not Found",
  "message": "Order not found: ...",
  "path": "/api/v1/orders/...",
  "validationErrors": null
}
```

## База данных

PostgreSQL запускается через Docker Compose.

```bash
docker compose up -d
```

Подключение:

```text
Host: localhost
Port: 5433
Database: tamada
User: tamada
```

Миграции Flyway находятся в:

```text
src/main/resources/db/migration
```

Текущие таблицы:

```text
composite_orders
booking_steps
flyway_schema_history
```

## Запуск

```bash
./mvnw spring-boot:run
```

Приложение доступно на:

```text
http://localhost:8080
```

## Тесты

Запуск:

```bash
./mvnw test
```

Реализованы тесты для:

- `BookingStep`
- `CompositeOrder`
- `CompositeOrderService`
- `CompositeOrderController`
- валидации API
- обработки ошибок

Текущий результат:

```text
Tests run: 30
Failures: 0
Errors: 0
Skipped: 0
```