package com.bank.payment.infrastructure.observability;

import com.bank.payment.infrastructure.persistence.repository.SpringDataOutboxRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/** Number of outbox events still waiting for Kafka; read on each Prometheus scrape. */
@Component
public class OutboxBacklogGauge {

    public OutboxBacklogGauge(MeterRegistry registry, SpringDataOutboxRepository outboxRepository) {
        Gauge.builder("mutabik.outbox.pending", outboxRepository, OutboxBacklogGauge::pending)
                .description("Outbox events not yet acknowledged by Kafka")
                .register(registry);
    }

    private static double pending(SpringDataOutboxRepository repository) {
        try {
            return repository.countByStatus("PENDING");
        } catch (RuntimeException ex) {
            return Double.NaN; // DB unavailable: report "unknown" rather than failing the scrape
        }
    }
}
