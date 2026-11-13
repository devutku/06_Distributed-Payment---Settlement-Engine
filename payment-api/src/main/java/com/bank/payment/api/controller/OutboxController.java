package com.bank.payment.api.controller;

import com.bank.payment.infrastructure.messaging.kafka.KafkaTransferEventListener;
import com.bank.payment.infrastructure.messaging.kafka.KafkaTransferEventPublisher;
import com.bank.payment.infrastructure.persistence.entity.OutboxJpaEntity;
import com.bank.payment.infrastructure.persistence.repository.SpringDataOutboxRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/outbox")
public class OutboxController {

    private final SpringDataOutboxRepository outboxRepository;
    private final KafkaTransferEventPublisher outboxPublisher;
    private final KafkaTransferEventListener kafkaEventListener;

    public OutboxController(
            SpringDataOutboxRepository outboxRepository,
            KafkaTransferEventPublisher outboxPublisher,
            KafkaTransferEventListener kafkaEventListener) {
        this.outboxRepository = outboxRepository;
        this.outboxPublisher = outboxPublisher;
        this.kafkaEventListener = kafkaEventListener;
    }

    public record OutboxEventDto(UUID id, String aggregateType, String aggregateId, String eventType,
                                 String payload, String status, Instant createdAt, Instant processedAt) {
        static OutboxEventDto from(OutboxJpaEntity e) {
            return new OutboxEventDto(e.getId(), e.getAggregateType(), e.getAggregateId(), e.getEventType(),
                    e.getPayload(), e.getStatus(), e.getCreatedAt(), e.getProcessedAt());
        }
    }

    /** Read inside a transaction: the {@code @Lob} payload cannot be streamed outside one on Postgres. */
    @GetMapping
    @Transactional(readOnly = true)
    public ResponseEntity<List<OutboxEventDto>> getOutboxEvents() {
        return ResponseEntity.ok(outboxRepository.findTop50ByOrderByCreatedAtDesc().stream()
                .map(OutboxEventDto::from).toList());
    }

    @PostMapping("/publish-now")
    public ResponseEntity<Map<String, String>> publishNow() {
        outboxPublisher.publishPendingOutboxEvents();
        return ResponseEntity.ok(Map.of("message", "Outbox publisher tetiklendi"));
    }

    @GetMapping("/kafka-status")
    public ResponseEntity<Map<String, Object>> getKafkaStatus() {
        return ResponseEntity.ok(Map.of(
                "processedEvents", kafkaEventListener.getProcessedEvents(),
                "deadLetterEvents", kafkaEventListener.getDeadLetterEvents()
        ));
    }
}

