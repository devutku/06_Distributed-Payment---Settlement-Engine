package com.bank.payment.infrastructure.messaging.kafka;

import com.bank.payment.infrastructure.persistence.entity.ProcessedEventJpaEntity;
import com.bank.payment.infrastructure.persistence.repository.SpringDataProcessedEventRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Component
public class KafkaTransferEventListener {

    private static final Logger log = LoggerFactory.getLogger(KafkaTransferEventListener.class);

    private final List<String> processedEvents = Collections.synchronizedList(new ArrayList<>());
    private final List<String> deadLetterEvents = Collections.synchronizedList(new ArrayList<>());

    private final SpringDataProcessedEventRepository processedEventRepository;
    private final ObjectMapper objectMapper;

    public KafkaTransferEventListener(SpringDataProcessedEventRepository processedEventRepository,
                                      ObjectMapper objectMapper) {
        this.processedEventRepository = processedEventRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * Idempotent consumer. Delivery is at-least-once, so the same event may arrive
     * more than once (outbox retry after a lost ack, consumer rebalance, ...).
     * The eventId is recorded in processed_events in the same transaction as the
     * side effects: a duplicate is skipped, and if processing fails the record is
     * rolled back so the retry can process the event again.
     */
    @KafkaListener(topics = KafkaConfig.TRANSFER_EVENTS_TOPIC, groupId = "settlement-consumer-group")
    @Transactional
    public void onTransferEvent(ConsumerRecord<String, String> record) {
        log.info("Kafka consumer received transfer event: key={}, partition={}, offset={}",
                record.key(), record.partition(), record.offset());

        String eventId = extractEventId(record.value());
        if (processedEventRepository.existsById(eventId)) {
            log.info("Duplicate transfer event [{}] at offset {} skipped (already processed)", eventId, record.offset());
            return;
        }
        // Primary key on event_id is the final guard if two consumers race on the same event
        processedEventRepository.saveAndFlush(new ProcessedEventJpaEntity(eventId, Instant.now()));

        // Simulated Poison Pill to demonstrate Retry & DLQ
        if (record.value().contains("SIMULATE_DLQ_FAILURE")) {
            log.error("Simulated poison pill encountered! Throwing exception to trigger DLQ retry flow...");
            throw new RuntimeException("Simulated transient processing error for Dead Letter Queue demonstration");
        }

        processedEvents.add("Offset: " + record.offset() + " | Key: " + record.key() + " | " + record.value());
    }

    private String extractEventId(String payload) {
        if (payload == null) {
            throw new IllegalArgumentException("Transfer event has no payload");
        }
        try {
            JsonNode eventId = objectMapper.readTree(payload).get("eventId");
            if (eventId == null || eventId.isNull() || eventId.asText().isBlank()) {
                throw new IllegalArgumentException("Transfer event has no eventId: " + payload);
            }
            return eventId.asText();
        } catch (JsonProcessingException e) {
            // Not retryable (see KafkaConfig): goes straight to the DLT
            throw new IllegalArgumentException("Transfer event payload is not valid JSON", e);
        }
    }

    @KafkaListener(topics = KafkaConfig.TRANSFER_EVENTS_DLT_TOPIC, groupId = "settlement-dlq-group")
    public void onDeadLetterEvent(ConsumerRecord<String, String> record) {
        log.warn("🚨 [DLQ ALERT] Message landed in Dead Letter Queue (DLT)! Key: {}, Payload: {}",
                record.key(), record.value());

        deadLetterEvents.add("DLQ [Key: " + record.key() + "] -> " + record.value());
    }

    public List<String> getProcessedEvents() {
        return List.copyOf(processedEvents);
    }

    public List<String> getDeadLetterEvents() {
        return List.copyOf(deadLetterEvents);
    }
}

