package com.tamada.travel.orchestrator.service.sse;

import java.util.function.Consumer;

/**
 * Интерфейс распределенной рассылки SSE-событий (Pub/Sub Abstraction).
 * Позволяет масштабировать Server-Sent Events на несколько экземпляров оркестратора
 * через Redis Pub/Sub, Kafka или локальный диспетчер.
 */
public interface OrderEventBroadcaster {

    void broadcast(OrderEvent event);

    void setConsumer(Consumer<OrderEvent> consumer);
}
