package com.tamada.travel.orchestrator.service;

import com.tamada.travel.orchestrator.config.OutboxKafkaProperties;
import com.tamada.travel.orchestrator.model.OutboxMessage;
import com.tamada.travel.orchestrator.model.OutboxStatus;
import com.tamada.travel.orchestrator.repository.OutboxRepository;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
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
 * Каждые несколько секунд вычитывает пачку сообщений со статусом PENDING из таблицы outbox_messages,
 * отправляет их в брокер сообщений Kafka (с сохранением aggregateId в качестве ключа партиционирования
 * для гарантии строгого порядка доставки).
 * <p>
 * При успешной отправке меняет статус записи на SENT, при ошибке — на FAILED.
 */
@Service
@Slf4j
public class OutboxEventPublisher implements OutboxPublisher {

    private final OutboxRepository outboxRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final OutboxKafkaProperties properties;
    private final com.tamada.travel.orchestrator.observability.SagaMetrics metrics;
    private final org.springframework.transaction.support.TransactionTemplate transactionTemplate;
    private final AtomicBoolean isPublishing = new AtomicBoolean(false);

    @Autowired
    public OutboxEventPublisher(
            OutboxRepository outboxRepository,
            @Autowired(required = false) KafkaTemplate<String, String> kafkaTemplate,
            OutboxKafkaProperties properties,
            @Autowired(required = false) com.tamada.travel.orchestrator.observability.SagaMetrics metrics,
            @Autowired(required = false) org.springframework.transaction.support.TransactionTemplate transactionTemplate
    ) {
        this.outboxRepository = Objects.requireNonNull(outboxRepository, "outboxRepository must not be null");
        this.kafkaTemplate = kafkaTemplate;
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
        this.metrics = metrics;
        this.transactionTemplate = transactionTemplate;

        if (metrics != null && metrics.getRegistry() != null) {
            io.micrometer.core.instrument.Gauge.builder("orchestrator.outbox.pending.backlog", outboxRepository,
                    repo -> repo.countByStatus(OutboxStatus.PENDING))
                    .description("Current number of pending outbox messages waiting to be published to Kafka")
                    .register(metrics.getRegistry());
        }
    }

    public OutboxEventPublisher(
            OutboxRepository outboxRepository,
            KafkaTemplate<String, String> kafkaTemplate,
            OutboxKafkaProperties properties,
            com.tamada.travel.orchestrator.observability.SagaMetrics metrics
    ) {
        this(outboxRepository, kafkaTemplate, properties, metrics, null);
    }

    public OutboxEventPublisher(
            OutboxRepository outboxRepository,
            KafkaTemplate<String, String> kafkaTemplate,
            OutboxKafkaProperties properties
    ) {
        this(outboxRepository, kafkaTemplate, properties, null, null);
    }

    @Scheduled(
            fixedDelayString = "${outbox.publisher.fixed-delay:2s}",
            initialDelayString = "${outbox.publisher.initial-delay:1s}"
    )
    public void scheduledPublish() {
        if (!properties.enabled() || kafkaTemplate == null) {
            return;
        }
        publishPendingEvents();
    }

    @Override
    public int publishPendingEvents() {
        if (!isPublishing.compareAndSet(false, true)) {
            log.debug("Outbox publishing is already in progress, skipping trigger");
            return 0;
        }

        try {
            int totalPublished = 0;
            int maxBatches = 20;
            int batchCount = 0;

            while (batchCount < maxBatches) {
                List<OutboxMessage> pendingMessages = fetchPendingBatch(properties.batchSize());
                if (pendingMessages.isEmpty()) {
                    break;
                }

                batchCount++;
                log.debug("Found {} pending outbox message(s) to publish (batch {}/{}, limit={})",
                        pendingMessages.size(), batchCount, maxBatches, properties.batchSize());

                int batchPublished = 0;
                for (OutboxMessage message : pendingMessages) {
                    boolean sent = publishSingleMessage(message);
                    if (sent) {
                        batchPublished++;
                    } else {
                        // Stop batch on failure to preserve event ordering for subsequent events
                        break;
                    }
                }

                totalPublished += batchPublished;
                if (batchPublished < pendingMessages.size() || pendingMessages.size() < properties.batchSize()) {
                    break;
                }
            }

            return totalPublished;
        } finally {
            isPublishing.set(false);
        }
    }

    protected List<OutboxMessage> fetchPendingBatch(int batchSize) {
        if (transactionTemplate != null) {
            return transactionTemplate.execute(status -> doFetchPendingBatch(batchSize));
        }
        return doFetchPendingBatch(batchSize);
    }

    private List<OutboxMessage> doFetchPendingBatch(int batchSize) {
        try {
            List<OutboxMessage> locked = outboxRepository.findAndLockPendingMessages(OutboxStatus.PENDING.name(), batchSize);
            if (locked != null && !locked.isEmpty()) {
                return locked;
            }
        } catch (Exception ex) {
            log.trace("Native skip locked query not supported or mocked, using standard repository lookup: {}", ex.getMessage());
        }
        Pageable pageable = PageRequest.of(0, batchSize);
        return outboxRepository.findByStatusOrderByCreatedAtAsc(OutboxStatus.PENDING, pageable);
    }

    @Override
    public boolean publishSingleMessage(OutboxMessage message) {
        if (kafkaTemplate == null) {
            log.warn("KafkaTemplate is not configured, marking outbox message id={} as FAILED", message.getId());
            message.markFailed();
            saveMessageStatus(message);
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
            message.markSent();
            saveMessageStatus(message);
            if (metrics != null) {
                metrics.incrementOutboxPublished(message.getEventType());
            }
            log.info("Successfully published outbox event id={} eventType={} for aggregateId={} to topic {}",
                    message.getId(), message.getEventType(), key, topic);
            return true;
        } catch (Exception e) {
            log.error("Failed to publish outbox event id={} to Kafka topic {}: {}", message.getId(), topic, e.getMessage());
            if (properties.dlqEnabled()) {
                forwardToDeadLetterQueue(message, record, e);
            } else {
                message.markFailed();
                saveMessageStatus(message);
            }
            return false;
        }
    }

    @Transactional
    public void saveMessageStatus(OutboxMessage message) {
        outboxRepository.save(message);
    }

    private void forwardToDeadLetterQueue(OutboxMessage message, ProducerRecord<String, String> originalRecord, Exception failure) {
        String dlqTopic = properties.dlqTopic();
        ProducerRecord<String, String> dlqRecord = new ProducerRecord<>(dlqTopic, originalRecord.key(), originalRecord.value());
        originalRecord.headers().forEach(h -> dlqRecord.headers().add(h));
        dlqRecord.headers().add(new RecordHeader("x-original-topic", properties.topic().getBytes(StandardCharsets.UTF_8)));
        String exMsg = failure.getMessage() != null ? failure.getMessage() : failure.getClass().getSimpleName();
        dlqRecord.headers().add(new RecordHeader("x-exception-message", exMsg.getBytes(StandardCharsets.UTF_8)));
        dlqRecord.headers().add(new RecordHeader("x-failed-at", java.time.Instant.now().toString().getBytes(StandardCharsets.UTF_8)));

        try {
            kafkaTemplate.send(dlqRecord).get(properties.sendTimeout().toMillis(), TimeUnit.MILLISECONDS);
            message.markDeadLetter();
            saveMessageStatus(message);
            if (metrics != null) {
                metrics.incrementOutboxDlq(message.getEventType());
            }
            log.warn("Routed failed outbox event id={} to DLQ topic {}", message.getId(), dlqTopic);
        } catch (Exception dlqEx) {
            log.error("Failed to forward outbox event id={} to DLQ topic {}: {}", message.getId(), dlqTopic, dlqEx.getMessage());
            message.markFailed();
            saveMessageStatus(message);
        }
    }
}
