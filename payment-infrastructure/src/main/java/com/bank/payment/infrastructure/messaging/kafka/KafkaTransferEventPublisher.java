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
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Component
public class KafkaTransferEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(KafkaTransferEventPublisher.class);

    static final long SEND_TIMEOUT_SECONDS = 10;

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
     *
     * An event is marked PUBLISHED only after the broker has acknowledged it
     * (producer acks=all). If a send fails or times out, the event stays PENDING
     * and is retried on the next poll. We stop at the first failure so that
     * events are never published out of order.
     */
    @Scheduled(fixedDelay = 1000)
    @Transactional
    public void publishPendingOutboxEvents() {
        List<OutboxJpaEntity> pendingEvents = outboxRepository.findByStatusOrderByCreatedAtAsc(
                "PENDING", PageRequest.of(0, 50));

        for (OutboxJpaEntity event : pendingEvents) {
            try {
                kafkaTemplate.send(KafkaConfig.TRANSFER_EVENTS_TOPIC, event.getAggregateId(), event.getPayload())
                        .get(SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                log.warn("Outbox publishing interrupted at event [{}]; will retry on next poll", event.getId());
                return;
            } catch (ExecutionException | TimeoutException | RuntimeException ex) {
                log.error("Kafka did not acknowledge Outbox event [{}]; leaving it PENDING for retry: {}",
                        event.getId(), ex.getMessage());
                return;
            }

            event.setStatus("PUBLISHED");
            event.setProcessedAt(Instant.now());
            outboxRepository.save(event);
            log.info("Published Outbox event [{}] to Kafka", event.getId());
        }
    }
}
