package com.bank.payment.infrastructure.messaging.kafka;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Component
public class KafkaTransferEventListener {

    private static final Logger log = LoggerFactory.getLogger(KafkaTransferEventListener.class);

    private final List<String> processedEvents = Collections.synchronizedList(new ArrayList<>());
    private final List<String> deadLetterEvents = Collections.synchronizedList(new ArrayList<>());

    @KafkaListener(topics = KafkaConfig.TRANSFER_EVENTS_TOPIC, groupId = "settlement-consumer-group")
    public void onTransferEvent(ConsumerRecord<String, String> record) {
        log.info("Kafka consumer received transfer event: key={}, partition={}, offset={}",
                record.key(), record.partition(), record.offset());

        // Simulated Poison Pill to demonstrate Non-Blocking Retry & DLQ
        if (record.value() != null && record.value().contains("SIMULATE_DLQ_FAILURE")) {
            log.error("Simulated poison pill encountered! Throwing exception to trigger DLQ retry flow...");
            throw new RuntimeException("Simulated transient processing error for Dead Letter Queue demonstration");
        }

        processedEvents.add("Offset: " + record.offset() + " | Key: " + record.key() + " | " + record.value());
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
