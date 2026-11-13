package com.bank.payment.infrastructure.messaging.kafka;

import com.bank.payment.infrastructure.messaging.TraceParent;
import com.bank.payment.infrastructure.persistence.entity.OutboxJpaEntity;
import com.bank.payment.infrastructure.persistence.repository.SpringDataOutboxRepository;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Component
public class KafkaTransferEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(KafkaTransferEventPublisher.class);

    /** Kafka client default for delivery.timeout.ms, used when the producer config does not set it. */
    static final long KAFKA_DEFAULT_DELIVERY_TIMEOUT_MS = 120_000;
    /** Extra time we wait beyond the producer's own delivery timeout. */
    static final long ACK_WAIT_MARGIN_MS = 2_000;

    private final SpringDataOutboxRepository outboxRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final long ackWaitMs;
    private final Tracer tracer;

    public KafkaTransferEventPublisher(
            SpringDataOutboxRepository outboxRepository,
            KafkaTemplate<String, String> kafkaTemplate,
            Tracer tracer) {
        this.outboxRepository = outboxRepository;
        this.kafkaTemplate = kafkaTemplate;
        this.tracer = tracer;
        this.ackWaitMs = deliveryTimeoutMs(kafkaTemplate) + ACK_WAIT_MARGIN_MS;
    }

    /**
     * Transactional Outbox Polling Publisher.
     * Guarantees At-Least-Once delivery semantics to Apache Kafka without Dual-Write issues.
     *
     * An event is marked PUBLISHED only after the broker has acknowledged it
     * (producer acks=all). If a send fails, the event stays PENDING and is retried
     * on the next poll. We stop at the first failure so that events are never
     * published out of order.
     *
     * We wait longer than the producer's delivery.timeout.ms, so by the time we give
     * up the producer has already either delivered the record or expired it. A record
     * we treat as failed can therefore not be delivered later behind our back, which
     * is what previously produced one duplicate per poll during a broker outage.
     *
     * Batches are claimed with FOR UPDATE SKIP LOCKED, so concurrent publishers
     * never pick up the same events.
     */
    @Scheduled(fixedDelay = 1000)
    @Transactional
    public void publishPendingOutboxEvents() {
        List<OutboxJpaEntity> pendingEvents = outboxRepository.claimBatchByStatus(
                "PENDING", PageRequest.of(0, 50));

        for (OutboxJpaEntity event : pendingEvents) {
            // Continue the trace of the request that wrote this event, so API -> outbox -> Kafka is one trace
            Span span = startPublishSpan(event);
            try (Tracer.SpanInScope ignored = tracer.withSpan(span)) {
                kafkaTemplate.send(KafkaConfig.TRANSFER_EVENTS_TOPIC, event.getAggregateId(), event.getPayload())
                        .get(ackWaitMs, TimeUnit.MILLISECONDS);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                span.error(ex);
                log.warn("Outbox publishing interrupted at event [{}]; will retry on next poll", event.getId());
                return;
            } catch (ExecutionException | TimeoutException | RuntimeException ex) {
                span.error(ex);
                log.error("Kafka did not acknowledge Outbox event [{}]; leaving it PENDING for retry: {}",
                        event.getId(), ex.getMessage());
                return;
            } finally {
                span.end();
            }

            event.setStatus("PUBLISHED");
            event.setProcessedAt(Instant.now());
            outboxRepository.save(event);
            log.info("Published Outbox event [{}] to Kafka", event.getId());
        }
    }

    /** Child of the stored request span when the outbox row carries a valid traceparent, otherwise a new span. */
    private Span startPublishSpan(OutboxJpaEntity event) {
        Span.Builder builder = tracer.spanBuilder();
        TraceParent.parse(event.getTraceParent(), tracer).ifPresent(builder::setParent);
        return builder.name("outbox.publish")
                .tag("outbox.event_type", String.valueOf(event.getEventType()))
                .start();
    }

    long ackWaitMs() {
        return ackWaitMs;
    }

    static long deliveryTimeoutMs(KafkaTemplate<String, String> kafkaTemplate) {
        ProducerFactory<String, String> factory = kafkaTemplate.getProducerFactory();
        Map<String, Object> config = factory != null ? factory.getConfigurationProperties() : null;
        Object value = config != null ? config.get(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG) : null;
        if (value == null) {
            return KAFKA_DEFAULT_DELIVERY_TIMEOUT_MS;
        }
        return value instanceof Number n ? n.longValue() : Long.parseLong(value.toString().trim());
    }
}
