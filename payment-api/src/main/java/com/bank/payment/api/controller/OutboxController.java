package com.bank.payment.api.controller;

import com.bank.payment.infrastructure.messaging.kafka.KafkaTransferEventListener;
import com.bank.payment.infrastructure.messaging.kafka.KafkaTransferEventPublisher;
import com.bank.payment.infrastructure.persistence.entity.OutboxJpaEntity;
import com.bank.payment.infrastructure.persistence.repository.SpringDataOutboxRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

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

    @GetMapping
    public ResponseEntity<List<OutboxJpaEntity>> getOutboxEvents() {
        return ResponseEntity.ok(outboxRepository.findTop50ByOrderByCreatedAtDesc());
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

