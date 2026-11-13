package com.bank.payment.infrastructure.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * Inbox / deduplication record for the transfer-events consumer.
 * Kafka delivery is at-least-once, so the same event can arrive more than once.
 * A row here means the event's side effects have already been committed.
 */
@Entity
@Table(name = "processed_events")
public class ProcessedEventJpaEntity {

    @Id
    @Column(name = "event_id", nullable = false, length = 64)
    private String eventId;

    @Column(name = "processed_at", nullable = false)
    private Instant processedAt;

    protected ProcessedEventJpaEntity() {}

    public ProcessedEventJpaEntity(String eventId, Instant processedAt) {
        this.eventId = eventId;
        this.processedAt = processedAt;
    }

    public String getEventId() { return eventId; }
    public Instant getProcessedAt() { return processedAt; }
}
