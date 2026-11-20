package com.tamada.travel.orchestrator.service;

import com.tamada.travel.orchestrator.config.OutboxKafkaProperties;
import com.tamada.travel.orchestrator.model.OutboxMessage;
import com.tamada.travel.orchestrator.model.OutboxStatus;
import com.tamada.travel.orchestrator.repository.OutboxRepository;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Фоновый воркер публикации Outbox-событий в Apache Kafka (Transactional Outbox Pattern).
 * <p>
 * Считывает из базы данных события заказов со статусом PENDING, отправляет их в топик Kafka
 * (с сохранением aggregateId в качестве ключа партиционирования для гарантии строгого порядка доставки)
 * и переводит в статус PROCESSED.
 */
@Service
@Slf4j
public class OutboxEventPublisher {

    private final OutboxRepository outboxRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final OutboxKafkaProperties properties;
    private final AtomicBoolean isPublishing = new AtomicBoolean(false);

    @Autowired
    public OutboxEventPublisher(
            OutboxRepository outboxRepository,
            @Autowired(required = false) KafkaTemplate<String, String> kafkaTemplate,
            OutboxKafkaProperties properties
    ) {
        this.outboxRepository = Objects.requireNonNull(outboxRepository, "outboxRepository must not be null");
        this.kafkaTemplate = kafkaTemplate;
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
    }

    @Scheduled(
            fixedDelayString = "${outbox.publisher.fixed-delay:1s}",
            initialDelayString = "${outbox.publisher.initial-delay:1s}"
    )
    public void scheduledPublish() {
        if (!properties.enabled() || kafkaTemplate == null) {
            return;
        }
        publishPendingEvents();
    }

    public int publishPendingEvents() {
        if (!isPublishing.compareAndSet(false, true)) {
            log.debug("Outbox publishing is already in progress, skipping trigger");
            return 0;
        }

        try {
            List<OutboxMessage> pendingMessages = outboxRepository.findByStatusOrderByCreatedAtAsc(OutboxStatus.PENDING);
            if (pendingMessages.isEmpty()) {
                return 0;
            }

            log.debug("Found {} pending outbox message(s) to publish", pendingMessages.size());
            int publishedCount = 0;
            int limit = Math.min(pendingMessages.size(), properties.batchSize());

            for (int i = 0; i < limit; i++) {
                OutboxMessage message = pendingMessages.get(i);
                boolean sent = publishSingleMessage(message);
                if (sent) {
                    publishedCount++;
                } else {
                    // Stop batch on failure to preserve event ordering for subsequent events
                    break;
                }
            }

            return publishedCount;
        } finally {
            isPublishing.set(false);
        }
    }

    @Transactional
    public boolean publishSingleMessage(OutboxMessage message) {
        if (kafkaTemplate == null) {
            return false;
        }

        String topic = properties.topic();
        String key = message.getAggregateId();
        String payload = message.getPayload();

        ProducerRecord<String, String> record = new ProducerRecord<>(topic, key, payload);
        if (message.getEventType() != null) {
            record.headers().add(new RecordHeader("eventType", message.getEventType().getBytes(StandardCharsets.UTF_8)));
        }
        if (message.getAggregateType() != null) {
            record.headers().add(new RecordHeader("aggregateType", message.getAggregateType().getBytes(StandardCharsets.UTF_8)));
        }
        if (message.getId() != null) {
            record.headers().add(new RecordHeader("outboxId", message.getId().toString().getBytes(StandardCharsets.UTF_8)));
        }

        try {
            kafkaTemplate.send(record).get(properties.sendTimeout().toMillis(), TimeUnit.MILLISECONDS);
            message.markProcessed();
            outboxRepository.save(message);
            log.info("Successfully published outbox event id={} eventType={} for aggregateId={} to topic {}",
                    message.getId(), message.getEventType(), key, topic);
            return true;
        } catch (Exception e) {
            log.warn("Failed to publish outbox event id={} to Kafka topic {}: {}", message.getId(), topic, e.getMessage());
            return false;
        }
    }
}
