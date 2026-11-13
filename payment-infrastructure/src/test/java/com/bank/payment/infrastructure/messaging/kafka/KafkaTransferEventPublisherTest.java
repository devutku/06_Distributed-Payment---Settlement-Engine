package com.bank.payment.infrastructure.messaging.kafka;

import com.bank.payment.infrastructure.persistence.entity.OutboxJpaEntity;
import com.bank.payment.infrastructure.persistence.repository.SpringDataOutboxRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class KafkaTransferEventPublisherTest {

    @Mock private SpringDataOutboxRepository outboxRepository;
    @Mock private KafkaTemplate<String, String> kafkaTemplate;

    private KafkaTransferEventPublisher publisher;

    @BeforeEach
    void setUp() {
        publisher = new KafkaTransferEventPublisher(outboxRepository, kafkaTemplate);
    }

    private static OutboxJpaEntity pending(String aggregateId) {
        return new OutboxJpaEntity(UUID.randomUUID(), "PAYMENT", aggregateId, "TransferSettled", "{}", "PENDING");
    }

    @Test
    @DisplayName("Events are marked PUBLISHED only after Kafka acknowledges them")
    void shouldMarkPublishedAfterAck() {
        OutboxJpaEntity e1 = pending("agg-1");
        OutboxJpaEntity e2 = pending("agg-2");
        when(outboxRepository.findByStatusOrderByCreatedAtAsc(eq("PENDING"), any(Pageable.class)))
                .thenReturn(List.of(e1, e2));
        when(kafkaTemplate.send(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(mock(SendResult.class)));

        publisher.publishPendingOutboxEvents();

        assertThat(e1.getStatus()).isEqualTo("PUBLISHED");
        assertThat(e2.getStatus()).isEqualTo("PUBLISHED");
        assertThat(e1.getProcessedAt()).isNotNull();
        verify(outboxRepository).save(e1);
        verify(outboxRepository).save(e2);
    }

    @Test
    @DisplayName("A failed send leaves the event PENDING and stops, so later events are not published out of order")
    void shouldKeepEventPendingWhenKafkaSendFails() {
        OutboxJpaEntity e1 = pending("agg-1");
        OutboxJpaEntity e2 = pending("agg-2");
        when(outboxRepository.findByStatusOrderByCreatedAtAsc(eq("PENDING"), any(Pageable.class)))
                .thenReturn(List.of(e1, e2));
        when(kafkaTemplate.send(anyString(), eq("agg-1"), anyString()))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("broker unavailable")));

        publisher.publishPendingOutboxEvents();

        assertThat(e1.getStatus()).isEqualTo("PENDING");
        assertThat(e2.getStatus()).isEqualTo("PENDING");
        verify(kafkaTemplate, never()).send(anyString(), eq("agg-2"), anyString());
        verify(outboxRepository, never()).save(any());
    }
}
