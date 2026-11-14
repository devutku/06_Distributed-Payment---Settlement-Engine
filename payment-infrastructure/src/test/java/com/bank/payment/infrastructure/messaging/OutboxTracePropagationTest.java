package com.bank.payment.infrastructure.messaging;

import com.bank.payment.domain.model.account.AccountId;
import com.bank.payment.domain.model.shared.Currency;
import com.bank.payment.domain.model.shared.Money;
import com.bank.payment.domain.model.transfer.IdempotencyKey;
import com.bank.payment.domain.model.transfer.TransferId;
import com.bank.payment.domain.event.TransferSettledEvent;
import com.bank.payment.infrastructure.messaging.kafka.KafkaTransferEventPublisher;
import com.bank.payment.infrastructure.persistence.adapter.OutboxPersistenceAdapter;
import com.bank.payment.infrastructure.persistence.entity.OutboxJpaEntity;
import com.bank.payment.infrastructure.persistence.repository.SpringDataOutboxRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.test.simple.SimpleSpan;
import io.micrometer.tracing.test.simple.SimpleTracer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Pageable;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class OutboxTracePropagationTest {

    private static final String STORED_TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736";
    private static final String STORED_SPAN_ID = "00f067aa0ba902b7";

    private SimpleTracer tracer;
    private SpringDataOutboxRepository repository;

    @BeforeEach
    void setUp() {
        tracer = new SimpleTracer();
        repository = mock(SpringDataOutboxRepository.class);
    }

    private TransferSettledEvent event() {
        return new TransferSettledEvent(TransferId.generate(), IdempotencyKey.of("k"),
                AccountId.generate(), AccountId.generate(), Money.of(1.00, Currency.TRY));
    }

    private OutboxJpaEntity savedEntity(OutboxPersistenceAdapter adapter) {
        ArgumentCaptor<OutboxJpaEntity> captor = ArgumentCaptor.forClass(OutboxJpaEntity.class);
        verify(repository).save(captor.capture());
        return captor.getValue();
    }

    @Test
    @DisplayName("Outbox row stores the W3C traceparent of the request's current span")
    void storesTraceparentOfCurrentSpan() {
        OutboxPersistenceAdapter adapter = new OutboxPersistenceAdapter(repository, new ObjectMapper().findAndRegisterModules(), tracer);
        Span request = tracer.nextSpan().name("http request").start();
        try (Tracer.SpanInScope ignored = tracer.withSpan(request)) {
            adapter.saveEvent(event());
        } finally {
            request.end();
        }

        // trace-flags must reflect the span's sampling decision (SimpleTracer spans are unsampled by default)
        String flags = Boolean.TRUE.equals(request.context().sampled()) ? "01" : "00";
        String expected = "00-" + request.context().traceId() + "-" + request.context().spanId() + "-" + flags;
        assertThat(savedEntity(adapter).getTraceParent()).isEqualTo(expected);
    }

    @Test
    @DisplayName("Without an active span the row is still written, with no traceparent")
    void storesNullWithoutActiveSpan() {
        OutboxPersistenceAdapter adapter = new OutboxPersistenceAdapter(repository, new ObjectMapper().findAndRegisterModules(), tracer);

        adapter.saveEvent(event());

        assertThat(savedEntity(adapter).getTraceParent()).isNull();
    }

    @SuppressWarnings("unchecked")
    private KafkaTransferEventPublisher publisherWith(OutboxJpaEntity pending) {
        KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);
        when(kafka.send(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(mock(SendResult.class)));
        when(repository.claimBatchByStatus(eq("PENDING"), any(Pageable.class))).thenReturn(List.of(pending));
        return new KafkaTransferEventPublisher(repository, kafka, tracer, new com.bank.payment.infrastructure.observability.PaymentMetrics(new io.micrometer.core.instrument.simple.SimpleMeterRegistry()));
    }

    private static OutboxJpaEntity pendingWithTraceParent(String traceParent) {
        OutboxJpaEntity e = new OutboxJpaEntity(UUID.randomUUID(), "PAYMENT", "agg-1", "TransferSettled", "{}", "PENDING");
        e.setTraceParent(traceParent);
        return e;
    }

    @Test
    @DisplayName("Publishing continues the stored trace: outbox.publish is a child of the request span")
    void publishContinuesStoredTrace() {
        OutboxJpaEntity pending = pendingWithTraceParent("00-" + STORED_TRACE_ID + "-" + STORED_SPAN_ID + "-01");

        publisherWith(pending).publishPendingOutboxEvents();

        SimpleSpan publish = tracer.getSpans().stream()
                .filter(s -> "outbox.publish".equals(s.getName())).findFirst().orElseThrow();
        assertThat(publish.context().traceId()).isEqualTo(STORED_TRACE_ID);
        assertThat(publish.context().parentId()).isEqualTo(STORED_SPAN_ID);
        assertThat(publish.getEndTimestamp().toEpochMilli()).isPositive();
        assertThat(pending.getStatus()).isEqualTo("PUBLISHED");
    }

    @Test
    @DisplayName("A malformed stored traceparent does not block publishing; a new root span is used")
    void malformedTraceparentStartsNewRoot() {
        OutboxJpaEntity pending = pendingWithTraceParent("not-a-traceparent");

        publisherWith(pending).publishPendingOutboxEvents();

        SimpleSpan publish = tracer.getSpans().stream()
                .filter(s -> "outbox.publish".equals(s.getName())).findFirst().orElseThrow();
        assertThat(publish.context().traceId()).isNotEqualTo(STORED_TRACE_ID);
        assertThat(pending.getStatus()).isEqualTo("PUBLISHED");
    }
}
