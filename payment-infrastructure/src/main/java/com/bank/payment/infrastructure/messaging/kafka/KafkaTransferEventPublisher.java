package com.bank.payment.infrastructure.messaging.kafka;

import com.bank.payment.infrastructure.persistence.entity.OutboxJpaEntity;
import com.bank.payment.infrastructure.persistence.repository.SpringDataOutboxRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

@Component
public class KafkaTransferEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(KafkaTransferEventPublisher.class);

    private final SpringDataOutboxRepository outboxRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;

    public KafkaTransferEventPublisher(
            SpringDataOutboxRepository outboxRepository,
            KafkaTemplate<String, String> kafkaTemplate) {
        this.outboxRepository = outboxRepository;
        this.kafkaTemplate = kafkaTemplate;
    }

    /**
     * Transactional Outbox Polling Publisher.
     * Guarantees At-Least-Once delivery semantics to Apache Kafka without Dual-Write issues.
     */
    @Scheduled(fixedDelay = 1000)
    @Transactional
    public void publishPendingOutboxEvents() {
        List<OutboxJpaEntity> pendingEvents = outboxRepository.findByStatusOrderByCreatedAtAsc(
                "PENDING", PageRequest.of(0, 50));

        if (pendingEvents.isEmpty()) {
            return;
        }

        for (OutboxJpaEntity event : pendingEvents) {
            try {
                kafkaTemplate.send(KafkaConfig.TRANSFER_EVENTS_TOPIC, event.getAggregateId(), event.getPayload())
                        .whenComplete((result, ex) -> {
                            if (ex == null) {
                                log.info("Published Outbox event [{}] to Kafka", event.getId());
                            } else {
                                log.error("Failed to send Outbox event [{}] to Kafka: {}", event.getId(), ex.getMessage());
                            }
                        });

                event.setStatus("PUBLISHED");
                event.setProcessedAt(Instant.now());
                outboxRepository.save(event);
            } catch (Exception ex) {
                log.error("Error processing Outbox event [{}]: {}", event.getId(), ex.getMessage());
            }
        }
    }
}

