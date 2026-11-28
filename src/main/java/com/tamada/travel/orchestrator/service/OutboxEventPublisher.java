package com.tamada.travel.orchestrator.service;

import com.tamada.travel.orchestrator.config.OutboxKafkaProperties;
import com.tamada.travel.orchestrator.model.OutboxMessage;
import com.tamada.travel.orchestrator.model.OutboxStatus;
import com.tamada.travel.orchestrator.observability.SagaMetrics;
import com.tamada.travel.orchestrator.repository.OutboxRepository;
import io.micrometer.core.instrument.Gauge;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.domain.SliceImpl;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Фоновый воркер публикации Outbox-событий в Apache Kafka (Transactional Outbox Pattern).
 * <p>
 * Каждые несколько секунд вычитывает пачку сообщений со статусом PENDING из таблицы outbox_messages
 * через {@link Slice} (без {@code SELECT count(*)}) и отправляет их в брокер сообщений Kafka
 * (с сохранением aggregateId в качестве ключа партиционирования для гарантии строгого порядка доставки).
 * <p>
 * После обработки пачки обновляет статусы всех обработанных записей пакетным запросом:
 * SENT при успешной отправке, DEAD_LETTER или FAILED при ошибке.
 */
@Service
@Slf4j
public class OutboxEventPublisher implements OutboxPublisher {

    private static final int MAX_BATCHES_PER_RUN = 20;

    private final OutboxRepository outboxRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final OutboxKafkaProperties properties;
    private final SagaMetrics metrics;
    private final TransactionTemplate transactionTemplate;
    private final AtomicBoolean isPublishing = new AtomicBoolean(false);

    @Autowired
    public OutboxEventPublisher(
            OutboxRepository outboxRepository,
            @Autowired(required = false) KafkaTemplate<String, String> kafkaTemplate,
            OutboxKafkaProperties properties,
            @Autowired(required = false) SagaMetrics metrics,
            @Autowired(required = false) TransactionTemplate transactionTemplate
    ) {
        this.outboxRepository = Objects.requireNonNull(outboxRepository, "outboxRepository must not be null");
        this.kafkaTemplate = kafkaTemplate;
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
        this.metrics = metrics;
        this.transactionTemplate = transactionTemplate;

        if (metrics != null && metrics.getRegistry() != null) {
            Gauge.builder("orchestrator.outbox.pending.backlog", outboxRepository,
                    repo -> repo.countByStatus(OutboxStatus.PENDING))
                    .description("Current number of pending outbox messages waiting to be published to Kafka")
                    .register(metrics.getRegistry());
        }
    }

    public OutboxEventPublisher(
            OutboxRepository outboxRepository,
            KafkaTemplate<String, String> kafkaTemplate,
            OutboxKafkaProperties properties,
            SagaMetrics metrics
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
            int batchCount = 0;

            while (batchCount < MAX_BATCHES_PER_RUN) {
                Slice<OutboxMessage> pendingSlice = fetchPendingBatch(properties.batchSize());
                if (pendingSlice == null || !pendingSlice.hasContent()) {
                    break;
                }

                List<OutboxMessage> pendingMessages = pendingSlice.getContent();
                batchCount++;
                log.debug("Found {} pending outbox message(s) to publish (batch {}/{}, limit={}, hasNext={})",
                        pendingMessages.size(), batchCount, MAX_BATCHES_PER_RUN, properties.batchSize(), pendingSlice.hasNext());

                int batchPublished = publishBatch(pendingMessages);
                totalPublished += batchPublished;

                if (batchPublished < pendingMessages.size() || !pendingSlice.hasNext()) {
                    break;
                }
            }

            return totalPublished;
        } finally {
            isPublishing.set(false);
        }
    }

    protected Slice<OutboxMessage> fetchPendingBatch(int batchSize) {
        if (transactionTemplate != null) {
            return transactionTemplate.execute(status -> doFetchPendingBatch(batchSize));
        }
        return doFetchPendingBatch(batchSize);
    }

    private Slice<OutboxMessage> doFetchPendingBatch(int batchSize) {
        Pageable pageable = PageRequest.of(0, batchSize);
        try {
            List<OutboxMessage> locked = outboxRepository.findAndLockPendingMessages(
                    OutboxStatus.PENDING.name(),
                    batchSize + 1
            );
            if (locked != null && !locked.isEmpty()) {
                boolean hasNext = locked.size() > batchSize;
                List<OutboxMessage> content = hasNext
                        ? new ArrayList<>(locked.subList(0, batchSize))
                        : locked;
                return new SliceImpl<>(content, pageable, hasNext);
            }
        } catch (Exception ex) {
            log.trace("Native skip locked query not supported or mocked, using standard repository lookup: {}", ex.getMessage());
        }
        Slice<OutboxMessage> slice = outboxRepository.findByStatusOrderByCreatedAtAsc(OutboxStatus.PENDING, pageable);
        return slice != null ? slice : new SliceImpl<>(List.of(), pageable, false);
    }

    private int publishBatch(List<OutboxMessage> pendingMessages) {
        List<OutboxMessage> processedMessages = new ArrayList<>(pendingMessages.size());
        int batchPublished = 0;

        for (OutboxMessage message : pendingMessages) {
            boolean sent = sendAndMarkStatus(message);
            processedMessages.add(message);
            if (sent) {
                batchPublished++;
            } else {
                // Stop batch on failure to preserve event ordering for subsequent events
                break;
            }
        }

        saveMessageStatuses(processedMessages);
        return batchPublished;
    }

    @Override
    public boolean publishSingleMessage(OutboxMessage message) {
        boolean sent = sendAndMarkStatus(message);
        saveMessageStatuses(List.of(message));
        return sent;
    }

    private boolean sendAndMarkStatus(OutboxMessage message) {
        if (kafkaTemplate == null) {
            log.warn("KafkaTemplate is not configured, marking outbox message id={} as FAILED", message.getId());
            message.markFailed();
            return false;
        }

        String topic = properties.topic();
        String key = message.getAggregateId();
        String payload = message.getPayload();

        ProducerRecord<String, String> record = buildProducerRecord(topic, key, payload, message);

        try {
            kafkaTemplate.send(record).get(properties.sendTimeout().toMillis(), TimeUnit.MILLISECONDS);
            message.markSent();
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
            }
            return false;
        }
    }

    private ProducerRecord<String, String> buildProducerRecord(
            String topic,
            String key,
            String payload,
            OutboxMessage message
    ) {
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
        return record;
    }

    @Transactional
    public void saveMessageStatus(OutboxMessage message) {
        if (message != null) {
            saveMessageStatuses(List.of(message));
        }
    }

    @Transactional
    public void saveMessageStatuses(List<OutboxMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return;
        }
        if (transactionTemplate != null) {
            transactionTemplate.executeWithoutResult(status -> doSaveMessageStatuses(messages));
            return;
        }
        doSaveMessageStatuses(messages);
    }

    private void doSaveMessageStatuses(List<OutboxMessage> messages) {
        Map<OutboxStatus, List<UUID>> idsByStatus = new EnumMap<>(OutboxStatus.class);
        Map<OutboxStatus, Instant> processedAtByStatus = new EnumMap<>(OutboxStatus.class);
        List<OutboxMessage> withoutIds = new ArrayList<>();

        for (OutboxMessage message : messages) {
            if (message.getId() == null) {
                withoutIds.add(message);
                continue;
            }
            idsByStatus.computeIfAbsent(message.getStatus(), ignored -> new ArrayList<>())
                    .add(message.getId());
            if (message.getProcessedAt() != null) {
                processedAtByStatus.putIfAbsent(message.getStatus(), message.getProcessedAt());
            }
        }

        for (Map.Entry<OutboxStatus, List<UUID>> entry : idsByStatus.entrySet()) {
            OutboxStatus status = entry.getKey();
            Instant processedAt = processedAtByStatus.getOrDefault(status, Instant.now());
            outboxRepository.updateStatusByIds(entry.getValue(), status, processedAt);
        }

        if (!withoutIds.isEmpty()) {
            outboxRepository.saveAll(withoutIds);
        }
    }

    private void forwardToDeadLetterQueue(OutboxMessage message, ProducerRecord<String, String> originalRecord, Exception failure) {
        String dlqTopic = properties.dlqTopic();
        ProducerRecord<String, String> dlqRecord = new ProducerRecord<>(dlqTopic, originalRecord.key(), originalRecord.value());
        originalRecord.headers().forEach(h -> dlqRecord.headers().add(h));
        dlqRecord.headers().add(new RecordHeader("x-original-topic", properties.topic().getBytes(StandardCharsets.UTF_8)));
        String exMsg = failure.getMessage() != null ? failure.getMessage() : failure.getClass().getSimpleName();
        dlqRecord.headers().add(new RecordHeader("x-exception-message", exMsg.getBytes(StandardCharsets.UTF_8)));
        dlqRecord.headers().add(new RecordHeader("x-failed-at", Instant.now().toString().getBytes(StandardCharsets.UTF_8)));

        try {
            kafkaTemplate.send(dlqRecord).get(properties.sendTimeout().toMillis(), TimeUnit.MILLISECONDS);
            message.markDeadLetter();
            if (metrics != null) {
                metrics.incrementOutboxDlq(message.getEventType());
            }
            log.warn("Routed failed outbox event id={} to DLQ topic {}", message.getId(), dlqTopic);
        } catch (Exception dlqEx) {
            log.error("Failed to forward outbox event id={} to DLQ topic {}: {}", message.getId(), dlqTopic, dlqEx.getMessage());
            message.markFailed();
        }
    }
}
