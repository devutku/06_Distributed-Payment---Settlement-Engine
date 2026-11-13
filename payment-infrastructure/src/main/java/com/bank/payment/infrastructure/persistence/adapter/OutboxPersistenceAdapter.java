package com.bank.payment.infrastructure.persistence.adapter;

import com.bank.payment.application.port.out.OutboxEventPort;
import com.bank.payment.domain.event.DomainEvent;
import com.bank.payment.infrastructure.messaging.TraceParent;
import com.bank.payment.infrastructure.persistence.entity.OutboxJpaEntity;
import com.bank.payment.infrastructure.persistence.repository.SpringDataOutboxRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.tracing.Tracer;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class OutboxPersistenceAdapter implements OutboxEventPort {

    private final SpringDataOutboxRepository outboxRepository;
    private final ObjectMapper objectMapper;
    private final Tracer tracer;

    public OutboxPersistenceAdapter(SpringDataOutboxRepository outboxRepository, ObjectMapper objectMapper, Tracer tracer) {
        this.outboxRepository = outboxRepository;
        this.objectMapper = objectMapper;
        this.tracer = tracer;
    }

    @Override
    public void saveEvent(DomainEvent event) {
        try {
            String payload = objectMapper.writeValueAsString(event);
            OutboxJpaEntity entity = new OutboxJpaEntity(
                    UUID.randomUUID(),
                    "PAYMENT",
                    event.getEventId().toString(),
                    event.getEventType(),
                    payload,
                    "PENDING"
            );
            // Remember the writing request's trace so the async publisher can continue it
            entity.setTraceParent(TraceParent.ofCurrentSpan(tracer));
            outboxRepository.save(entity);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("DomainEvent serialization failed", e);
        }
    }
}

