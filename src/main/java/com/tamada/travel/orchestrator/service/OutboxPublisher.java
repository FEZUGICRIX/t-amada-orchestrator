package com.tamada.travel.orchestrator.service;

import com.tamada.travel.orchestrator.model.OutboxMessage;

/**
 * Фоновый воркер отправки событий из Outbox во внешнюю очередь / брокер сообщений.
 */
public interface OutboxPublisher {

    /**
     * Вычитывает пачку сообщений со статусом PENDING и отправляет их в брокер сообщений.
     *
     * @return количество успешно отправленных сообщений
     */
    int publishPendingEvents();

    /**
     * Отправляет единичное сообщение во внешнюю очередь / брокер сообщений.
     * При успешной отправке меняет статус записи на SENT, при ошибке — на FAILED.
     *
     * @param message сообщение из таблицы outbox_messages
     * @return true при успешной отправке, false при ошибке
     */
    boolean publishSingleMessage(OutboxMessage message);
}
