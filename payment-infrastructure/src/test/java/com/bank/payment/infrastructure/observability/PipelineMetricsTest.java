package com.bank.payment.infrastructure.observability;

import com.bank.payment.application.security.InvalidWebhookSignatureException;
import com.bank.payment.domain.exception.AccountNotFoundException;
import com.bank.payment.domain.exception.InsufficientFundsException;
import com.bank.payment.domain.model.account.AccountId;
import com.bank.payment.infrastructure.messaging.kafka.KafkaConfig;
import com.bank.payment.infrastructure.messaging.kafka.KafkaTransferEventListener;
import com.bank.payment.infrastructure.messaging.kafka.KafkaTransferEventPublisher;
import com.bank.payment.infrastructure.persistence.entity.OutboxJpaEntity;
import com.bank.payment.infrastructure.persistence.repository.SpringDataOutboxRepository;
import com.bank.payment.infrastructure.persistence.repository.SpringDataProcessedEventRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.tracing.Tracer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;
import org.springframework.kafka.core.KafkaTemplate;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class PipelineMetricsTest {

    private SimpleMeterRegistry registry;
    private PaymentMetrics metrics;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        metrics = new PaymentMetrics(registry);
    }

    private double counter(String name) {
        var c = registry.find(name).counter();
        return c == null ? 0 : c.count();
    }

    @Test
    @DisplayName("Exceptions map to outcomes; business rejections are never 'error'")
    void outcomeMapping() {
        assertThat(PaymentMetrics.outcomeOf(new InsufficientFundsException("x"))).isEqualTo("insufficient_funds");
        assertThat(PaymentMetrics.outcomeOf(new AccountNotFoundException(AccountId.generate()))).isEqualTo("not_found");
        assertThat(PaymentMetrics.outcomeOf(new IllegalArgumentException("x"))).isEqualTo("rejected");
        assertThat(PaymentMetrics.outcomeOf(new InvalidWebhookSignatureException("bad"))).isEqualTo("invalid_signature");
        assertThat(PaymentMetrics.outcomeOf(new RuntimeException("boom"))).isEqualTo("error");
    }

    @Test
    @DisplayName("A Kafka send failure increments the outbox publish-failure counter")
    @SuppressWarnings("unchecked")
    void publishFailureIsCounted() {
        SpringDataOutboxRepository repo = mock(SpringDataOutboxRepository.class);
        KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);
        OutboxJpaEntity pending = new OutboxJpaEntity(UUID.randomUUID(), "PAYMENT", "agg", "TransferSettled", "{}", "PENDING");
        when(repo.claimBatchByStatus(eq("PENDING"), any(Pageable.class))).thenReturn(List.of(pending));
        when(kafka.send(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("broker down")));

        new KafkaTransferEventPublisher(repo, kafka, Tracer.NOOP, metrics).publishPendingOutboxEvents();

        assertThat(counter("mutabik.outbox.publish.failures")).isEqualTo(1.0);
    }

    @Test
    @DisplayName("Duplicate deliveries and dead-letter messages are counted")
    void consumerSideCounters() {
        SpringDataProcessedEventRepository processed = mock(SpringDataProcessedEventRepository.class);
        when(processed.existsById("evt-1")).thenReturn(true);
        KafkaTransferEventListener listener = new KafkaTransferEventListener(processed, new ObjectMapper(), metrics);

        listener.onTransferEvent(new ConsumerRecord<>(KafkaConfig.TRANSFER_EVENTS_TOPIC, 0, 1L, "agg", "{\"eventId\":\"evt-1\"}"));
        listener.onDeadLetterEvent(new ConsumerRecord<>(KafkaConfig.TRANSFER_EVENTS_DLT_TOPIC, 0, 1L, "agg", "{}"));

        assertThat(counter("mutabik.kafka.duplicates.skipped")).isEqualTo(1.0);
        assertThat(counter("mutabik.kafka.dlq.messages")).isEqualTo(1.0);
    }
}
