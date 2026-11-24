package com.tamada.travel.orchestrator.service.sse;

import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.function.Consumer;

/**
 * Локальный рассыльщик событий заказа внутри одного экземпляра приложения.
 */
@Component
@Primary
public class LocalOrderEventBroadcaster implements OrderEventBroadcaster {

    private Consumer<OrderEvent> consumer;

    @Override
    public void setConsumer(Consumer<OrderEvent> consumer) {
        this.consumer = consumer;
    }

    @Override
    public void broadcast(OrderEvent event) {
        if (consumer != null && event != null) {
            consumer.accept(event);
        }
    }
}
