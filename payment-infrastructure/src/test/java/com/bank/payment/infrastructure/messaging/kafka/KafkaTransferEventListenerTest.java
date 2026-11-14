package com.bank.payment.infrastructure.messaging.kafka;

import com.bank.payment.infrastructure.persistence.entity.ProcessedEventJpaEntity;
import com.bank.payment.infrastructure.persistence.repository.SpringDataProcessedEventRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class KafkaTransferEventListenerTest {

    @Mock private SpringDataProcessedEventRepository processedEventRepository;

    private KafkaTransferEventListener listener;
    private final Set<String> stored = new HashSet<>();

    @BeforeEach
    void setUp() {
        listener = new KafkaTransferEventListener(processedEventRepository, new ObjectMapper(), new com.bank.payment.infrastructure.observability.PaymentMetrics(new io.micrometer.core.instrument.simple.SimpleMeterRegistry()));
        lenient().when(processedEventRepository.existsById(anyString())).thenAnswer(inv -> stored.contains(inv.getArgument(0)));
        lenient().when(processedEventRepository.saveAndFlush(any())).thenAnswer(inv -> {
            stored.add(((ProcessedEventJpaEntity) inv.getArgument(0)).getEventId());
            return inv.getArgument(0);
        });
    }

    private static ConsumerRecord<String, String> record(long offset, String payload) {
        return new ConsumerRecord<>(KafkaConfig.TRANSFER_EVENTS_TOPIC, 0, offset, "agg-1", payload);
    }

    @Test
    @DisplayName("The same event delivered several times is processed exactly once")
    void shouldProcessDuplicateDeliveriesOnce() {
        String payload = "{\"eventId\":\"6f1c2b9e-0000-4000-8000-000000000001\",\"eventType\":\"TransferSettled\"}";

        listener.onTransferEvent(record(10, payload));
        listener.onTransferEvent(record(11, payload));
        listener.onTransferEvent(record(12, payload));

        assertThat(listener.getProcessedEvents()).hasSize(1);
        verify(processedEventRepository, times(1)).saveAndFlush(any());
    }

    @Test
    @DisplayName("Different events are each processed")
    void shouldProcessDistinctEvents() {
        listener.onTransferEvent(record(1, "{\"eventId\":\"a\"}"));
        listener.onTransferEvent(record(2, "{\"eventId\":\"b\"}"));

        assertThat(listener.getProcessedEvents()).hasSize(2);
    }

    @Test
    @DisplayName("An event without an eventId is rejected as non-retryable instead of being processed")
    void shouldRejectEventWithoutEventId() {
        assertThatThrownBy(() -> listener.onTransferEvent(record(1, "{\"eventType\":\"TransferSettled\"}")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> listener.onTransferEvent(record(2, "not json")))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(listener.getProcessedEvents()).isEmpty();
        verify(processedEventRepository, never()).saveAndFlush(any());
    }
}
