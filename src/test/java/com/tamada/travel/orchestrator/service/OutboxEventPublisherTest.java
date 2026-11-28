package com.tamada.travel.orchestrator.service;

import com.tamada.travel.orchestrator.config.OutboxKafkaProperties;
import com.tamada.travel.orchestrator.model.OutboxMessage;
import com.tamada.travel.orchestrator.model.OutboxStatus;
import com.tamada.travel.orchestrator.repository.OutboxRepository;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.SliceImpl;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OutboxEventPublisherTest {

    @Mock
    private OutboxRepository outboxRepository;

    @Mock
    private KafkaTemplate<String, String> kafkaTemplate;

    private OutboxKafkaProperties properties;
    private OutboxEventPublisher publisher;

    @BeforeEach
    void setUp() {
        properties = new OutboxKafkaProperties(
                true,
                "tamada.order-events",
                true,
                "tamada.order-events.DLQ",
                10,
                Duration.ofSeconds(2),
                Duration.ofSeconds(1),
                Duration.ofSeconds(2)
        );
        publisher = new OutboxEventPublisher(outboxRepository, kafkaTemplate, properties);
    }

    @Test
    void shouldPublishPendingMessagesToKafkaAndBatchUpdateSentStatus() {
        UUID orderId = UUID.randomUUID();
        UUID messageId = UUID.randomUUID();
        OutboxMessage message = createMessage(messageId, orderId.toString(), "ORDER_COMPLETED", "{\"orderId\":\"" + orderId + "\"}");

        when(outboxRepository.findByStatusOrderByCreatedAtAsc(eq(OutboxStatus.PENDING), any(Pageable.class)))
                .thenReturn(new SliceImpl<>(List.of(message), PageRequest.of(0, 10), false));
        when(kafkaTemplate.send(any(ProducerRecord.class)))
                .thenReturn(completedSendFuture("tamada.order-events", orderId.toString(), message.getPayload()));

        int publishedCount = publisher.publishPendingEvents();

        assertEquals(1, publishedCount);
        assertEquals(OutboxStatus.SENT, message.getStatus());
        assertNotNull(message.getProcessedAt());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<ProducerRecord<String, String>> recordCaptor = ArgumentCaptor.forClass(ProducerRecord.class);
        verify(kafkaTemplate).send(recordCaptor.capture());

        ProducerRecord<String, String> captured = recordCaptor.getValue();
        assertEquals("tamada.order-events", captured.topic());
        assertEquals(orderId.toString(), captured.key());
        assertEquals(message.getPayload(), captured.value());
        assertNotNull(captured.headers().lastHeader("eventType"));
        assertEquals("ORDER_COMPLETED", new String(captured.headers().lastHeader("eventType").value()));

        verify(outboxRepository).updateStatusByIds(eq(List.of(messageId)), eq(OutboxStatus.SENT), any(Instant.class));
        verify(outboxRepository, never()).save(any());
    }

    @Test
    void shouldBatchUpdateAllPublishedMessagesInSingleQuery() {
        UUID id1 = UUID.randomUUID();
        UUID id2 = UUID.randomUUID();
        UUID id3 = UUID.randomUUID();
        OutboxMessage m1 = createMessage(id1, "order-1", "ORDER_CREATED", "{}");
        OutboxMessage m2 = createMessage(id2, "order-2", "ORDER_PROCESSING", "{}");
        OutboxMessage m3 = createMessage(id3, "order-3", "ORDER_COMPLETED", "{}");

        when(outboxRepository.findByStatusOrderByCreatedAtAsc(eq(OutboxStatus.PENDING), any(Pageable.class)))
                .thenReturn(new SliceImpl<>(List.of(m1, m2, m3), PageRequest.of(0, 10), false));
        when(kafkaTemplate.send(any(ProducerRecord.class)))
                .thenReturn(completedSendFuture("tamada.order-events", "order", "{}"));

        int publishedCount = publisher.publishPendingEvents();

        assertEquals(3, publishedCount);
        verify(kafkaTemplate, times(3)).send(any(ProducerRecord.class));
        verify(outboxRepository, times(1)).updateStatusByIds(eq(List.of(id1, id2, id3)), eq(OutboxStatus.SENT), any(Instant.class));
        verify(outboxRepository, never()).save(any());
    }

    @Test
    void shouldStopWithoutExtraQueryWhenSliceHasNoNextEvenIfBatchIsFull() {
        OutboxKafkaProperties batchOfTwo = new OutboxKafkaProperties(
                true, "tamada.order-events", false, "tamada.order-events.DLQ", 2,
                Duration.ofSeconds(2), Duration.ofSeconds(1), Duration.ofSeconds(2)
        );
        OutboxEventPublisher smallBatchPublisher = new OutboxEventPublisher(outboxRepository, kafkaTemplate, batchOfTwo);

        UUID id1 = UUID.randomUUID();
        UUID id2 = UUID.randomUUID();
        OutboxMessage m1 = createMessage(id1, "order-1", "ORDER_CREATED", "{}");
        OutboxMessage m2 = createMessage(id2, "order-2", "ORDER_COMPLETED", "{}");

        when(outboxRepository.findByStatusOrderByCreatedAtAsc(eq(OutboxStatus.PENDING), eq(PageRequest.of(0, 2))))
                .thenReturn(new SliceImpl<>(List.of(m1, m2), PageRequest.of(0, 2), false));
        when(kafkaTemplate.send(any(ProducerRecord.class)))
                .thenReturn(completedSendFuture("tamada.order-events", "order", "{}"));

        int publishedCount = smallBatchPublisher.publishPendingEvents();

        assertEquals(2, publishedCount);
        verify(outboxRepository, times(1)).findByStatusOrderByCreatedAtAsc(eq(OutboxStatus.PENDING), eq(PageRequest.of(0, 2)));
        verify(outboxRepository, times(1)).updateStatusByIds(eq(List.of(id1, id2)), eq(OutboxStatus.SENT), any(Instant.class));
    }

    @Test
    void shouldFetchNextBatchWhenSliceHasNext() {
        OutboxKafkaProperties batchOfOne = new OutboxKafkaProperties(
                true, "tamada.order-events", false, "tamada.order-events.DLQ", 1,
                Duration.ofSeconds(2), Duration.ofSeconds(1), Duration.ofSeconds(2)
        );
        OutboxEventPublisher singleBatchPublisher = new OutboxEventPublisher(outboxRepository, kafkaTemplate, batchOfOne);

        UUID id1 = UUID.randomUUID();
        UUID id2 = UUID.randomUUID();
        OutboxMessage m1 = createMessage(id1, "order-1", "ORDER_CREATED", "{}");
        OutboxMessage m2 = createMessage(id2, "order-2", "ORDER_COMPLETED", "{}");

        when(outboxRepository.findByStatusOrderByCreatedAtAsc(eq(OutboxStatus.PENDING), eq(PageRequest.of(0, 1))))
                .thenReturn(new SliceImpl<>(List.of(m1), PageRequest.of(0, 1), true))
                .thenReturn(new SliceImpl<>(List.of(m2), PageRequest.of(0, 1), false));
        when(kafkaTemplate.send(any(ProducerRecord.class)))
                .thenReturn(completedSendFuture("tamada.order-events", "order", "{}"));

        int publishedCount = singleBatchPublisher.publishPendingEvents();

        assertEquals(2, publishedCount);
        verify(outboxRepository, times(2)).findByStatusOrderByCreatedAtAsc(eq(OutboxStatus.PENDING), eq(PageRequest.of(0, 1)));
        verify(outboxRepository).updateStatusByIds(eq(List.of(id1)), eq(OutboxStatus.SENT), any(Instant.class));
        verify(outboxRepository).updateStatusByIds(eq(List.of(id2)), eq(OutboxStatus.SENT), any(Instant.class));
    }

    @Test
    void shouldReturnZeroWhenNoPendingMessages() {
        when(outboxRepository.findByStatusOrderByCreatedAtAsc(eq(OutboxStatus.PENDING), any(Pageable.class)))
                .thenReturn(new SliceImpl<>(List.of(), PageRequest.of(0, 10), false));

        int publishedCount = publisher.publishPendingEvents();

        assertEquals(0, publishedCount);
        verifyNoInteractions(kafkaTemplate);
        verify(outboxRepository, never()).updateStatusByIds(any(), any(), any());
    }

    @Test
    void shouldStopBatchOnKafkaFailureAndBatchUpdateProcessedMessagesOnly() {
        UUID orderId = UUID.randomUUID();
        UUID id1 = UUID.randomUUID();
        UUID id2 = UUID.randomUUID();
        UUID id3 = UUID.randomUUID();
        OutboxMessage message1 = createMessage(id1, orderId.toString(), "ORDER_CREATED", "{}");
        OutboxMessage message2 = createMessage(id2, orderId.toString(), "ORDER_PROCESSING", "{}");
        OutboxMessage message3 = createMessage(id3, orderId.toString(), "ORDER_COMPLETED", "{}");

        when(outboxRepository.findByStatusOrderByCreatedAtAsc(eq(OutboxStatus.PENDING), any(Pageable.class)))
                .thenReturn(new SliceImpl<>(List.of(message1, message2, message3), PageRequest.of(0, 10), false));

        CompletableFuture<SendResult<String, String>> failedFuture = new CompletableFuture<>();
        failedFuture.completeExceptionally(new RuntimeException("Kafka broker down"));

        when(kafkaTemplate.send(any(ProducerRecord.class)))
                .thenReturn(completedSendFuture("tamada.order-events", orderId.toString(), "{}"))
                .thenReturn(failedFuture)
                .thenReturn(failedFuture);

        int publishedCount = publisher.publishPendingEvents();

        assertEquals(1, publishedCount);
        assertEquals(OutboxStatus.SENT, message1.getStatus());
        assertEquals(OutboxStatus.FAILED, message2.getStatus());
        assertNotNull(message2.getProcessedAt());
        verify(outboxRepository).updateStatusByIds(eq(List.of(id1)), eq(OutboxStatus.SENT), any(Instant.class));
        verify(outboxRepository).updateStatusByIds(eq(List.of(id2)), eq(OutboxStatus.FAILED), any(Instant.class));

        // message3 was not attempted to preserve strict FIFO ordering and remains PENDING
        assertEquals(OutboxStatus.PENDING, message3.getStatus());
        verify(kafkaTemplate, times(3)).send(any(ProducerRecord.class));
        verify(outboxRepository, never()).save(any());
    }

    @Test
    void shouldForwardToDlqAndMarkDeadLetterWhenPrimaryTopicFails() {
        UUID messageId = UUID.randomUUID();
        OutboxMessage message = createMessage(messageId, "order-123", "ORDER_CREATED", "{}");

        CompletableFuture<SendResult<String, String>> failedFuture = new CompletableFuture<>();
        failedFuture.completeExceptionally(new RuntimeException("Primary topic down"));

        when(kafkaTemplate.send(any(ProducerRecord.class)))
                .thenReturn(failedFuture)
                .thenReturn(completedSendFuture("tamada.order-events.DLQ", "order-123", "{}"));

        boolean result = publisher.publishSingleMessage(message);

        assertFalse(result);
        assertEquals(OutboxStatus.DEAD_LETTER, message.getStatus());
        verify(kafkaTemplate, times(2)).send(any(ProducerRecord.class));
        verify(outboxRepository).updateStatusByIds(eq(List.of(messageId)), eq(OutboxStatus.DEAD_LETTER), any(Instant.class));
    }

    @Test
    void shouldMarkFailedWhenKafkaTemplateIsNull() {
        OutboxEventPublisher publisherWithoutKafka = new OutboxEventPublisher(outboxRepository, null, properties);
        UUID messageId = UUID.randomUUID();
        OutboxMessage message = createMessage(messageId, "order-123", "ORDER_CREATED", "{}");

        boolean result = publisherWithoutKafka.publishSingleMessage(message);

        assertFalse(result);
        assertEquals(OutboxStatus.FAILED, message.getStatus());
        assertNotNull(message.getProcessedAt());
        verify(outboxRepository).updateStatusByIds(eq(List.of(messageId)), eq(OutboxStatus.FAILED), any(Instant.class));
    }

    @Test
    void shouldFallbackToSaveAllForMessagesWithoutId() {
        OutboxEventPublisher publisherWithoutKafka = new OutboxEventPublisher(outboxRepository, null, properties);
        OutboxMessage transientMessage = new OutboxMessage("COMPOSITE_ORDER", "order-123", "ORDER_CREATED", "{}");

        boolean result = publisherWithoutKafka.publishSingleMessage(transientMessage);

        assertFalse(result);
        assertEquals(OutboxStatus.FAILED, transientMessage.getStatus());
        verify(outboxRepository).saveAll(List.of(transientMessage));
        verify(outboxRepository, never()).updateStatusByIds(any(Collection.class), any(), any());
    }

    @Test
    void shouldSkipWhenPublisherDisabled() {
        OutboxKafkaProperties disabledProps = new OutboxKafkaProperties(
                false, "tamada.order-events", false, "tamada.order-events.DLQ", 10,
                Duration.ofSeconds(2), Duration.ofSeconds(1), Duration.ofSeconds(2)
        );
        OutboxEventPublisher disabledPublisher = new OutboxEventPublisher(outboxRepository, kafkaTemplate, disabledProps);

        disabledPublisher.scheduledPublish();

        verifyNoInteractions(outboxRepository);
        verifyNoInteractions(kafkaTemplate);
    }

    private OutboxMessage createMessage(UUID id, String aggregateId, String eventType, String payload) {
        OutboxMessage message = new OutboxMessage("COMPOSITE_ORDER", aggregateId, eventType, payload);
        ReflectionTestUtils.setField(message, "id", id);
        return message;
    }

    private CompletableFuture<SendResult<String, String>> completedSendFuture(String topic, String key, String payload) {
        RecordMetadata metadata = new RecordMetadata(
                new TopicPartition(topic, 0),
                0, 0, System.currentTimeMillis(), 0, 0
        );
        ProducerRecord<String, String> dummyRecord = new ProducerRecord<>(topic, key, payload);
        return CompletableFuture.completedFuture(new SendResult<>(dummyRecord, metadata));
    }
}
