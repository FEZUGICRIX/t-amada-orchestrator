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
import org.springframework.data.domain.Pageable;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
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
    void shouldPublishPendingMessagesToKafkaAndMarkSent() {
        UUID orderId = UUID.randomUUID();
        UUID messageId = UUID.randomUUID();
        OutboxMessage message = new OutboxMessage(
                "COMPOSITE_ORDER",
                orderId.toString(),
                "ORDER_COMPLETED",
                "{\"orderId\":\"" + orderId + "\"}"
        );
        ReflectionTestUtils.setField(message, "id", messageId);

        when(outboxRepository.findByStatusOrderByCreatedAtAsc(eq(OutboxStatus.PENDING), any(Pageable.class)))
                .thenReturn(List.of(message));

        RecordMetadata metadata = new RecordMetadata(
                new TopicPartition("tamada.order-events", 0),
                0, 0, System.currentTimeMillis(), 0, 0
        );
        ProducerRecord<String, String> dummyRecord = new ProducerRecord<>("tamada.order-events", orderId.toString(), message.getPayload());
        SendResult<String, String> sendResult = new SendResult<>(dummyRecord, metadata);
        CompletableFuture<SendResult<String, String>> future = CompletableFuture.completedFuture(sendResult);

        when(kafkaTemplate.send(any(ProducerRecord.class))).thenReturn(future);

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

        verify(outboxRepository).save(message);
    }

    @Test
    void shouldReturnZeroWhenNoPendingMessages() {
        when(outboxRepository.findByStatusOrderByCreatedAtAsc(eq(OutboxStatus.PENDING), any(Pageable.class)))
                .thenReturn(List.of());

        int publishedCount = publisher.publishPendingEvents();

        assertEquals(0, publishedCount);
        verifyNoInteractions(kafkaTemplate);
    }

    @Test
    void shouldStopBatchOnKafkaFailureAndMarkMessageFailed() {
        UUID orderId = UUID.randomUUID();
        OutboxMessage message1 = new OutboxMessage("COMPOSITE_ORDER", orderId.toString(), "ORDER_CREATED", "{}");
        OutboxMessage message2 = new OutboxMessage("COMPOSITE_ORDER", orderId.toString(), "ORDER_COMPLETED", "{}");

        when(outboxRepository.findByStatusOrderByCreatedAtAsc(eq(OutboxStatus.PENDING), any(Pageable.class)))
                .thenReturn(List.of(message1, message2));

        CompletableFuture<SendResult<String, String>> failedFuture = new CompletableFuture<>();
        failedFuture.completeExceptionally(new RuntimeException("Kafka broker down"));

        when(kafkaTemplate.send(any(ProducerRecord.class))).thenReturn(failedFuture);

        int publishedCount = publisher.publishPendingEvents();

        assertEquals(0, publishedCount);
        // message1 is marked FAILED upon sending error and saved
        assertEquals(OutboxStatus.FAILED, message1.getStatus());
        assertNotNull(message1.getProcessedAt());
        verify(outboxRepository).save(message1);

        // message2 was not attempted to preserve strict FIFO ordering and remains PENDING
        assertEquals(OutboxStatus.PENDING, message2.getStatus());
        verify(kafkaTemplate, times(2)).send(any(ProducerRecord.class));
        verify(outboxRepository, never()).save(message2);
    }

    @Test
    void shouldForwardToDlqAndMarkDeadLetterWhenPrimaryTopicFails() {
        OutboxMessage message = new OutboxMessage("COMPOSITE_ORDER", "order-123", "ORDER_CREATED", "{}");

        CompletableFuture<SendResult<String, String>> failedFuture = new CompletableFuture<>();
        failedFuture.completeExceptionally(new RuntimeException("Primary topic down"));

        CompletableFuture<SendResult<String, String>> successFuture = CompletableFuture.completedFuture(
                new SendResult<>(new ProducerRecord<>("tamada.order-events.DLQ", "order-123", "{}"),
                        new RecordMetadata(new TopicPartition("tamada.order-events.DLQ", 0), 0, 0, 0, 0, 0))
        );

        when(kafkaTemplate.send(any(ProducerRecord.class)))
                .thenReturn(failedFuture)
                .thenReturn(successFuture);

        boolean result = publisher.publishSingleMessage(message);

        assertFalse(result);
        assertEquals(OutboxStatus.DEAD_LETTER, message.getStatus());
        verify(kafkaTemplate, times(2)).send(any(ProducerRecord.class));
        verify(outboxRepository).save(message);
    }

    @Test
    void shouldMarkFailedWhenKafkaTemplateIsNull() {
        OutboxEventPublisher publisherWithoutKafka = new OutboxEventPublisher(outboxRepository, null, properties);
        OutboxMessage message = new OutboxMessage("COMPOSITE_ORDER", "order-123", "ORDER_CREATED", "{}");

        boolean result = publisherWithoutKafka.publishSingleMessage(message);

        assertFalse(result);
        assertEquals(OutboxStatus.FAILED, message.getStatus());
        assertNotNull(message.getProcessedAt());
        verify(outboxRepository).save(message);
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
}
