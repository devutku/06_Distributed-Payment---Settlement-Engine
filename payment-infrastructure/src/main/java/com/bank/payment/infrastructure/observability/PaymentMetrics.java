package com.bank.payment.infrastructure.observability;

import com.bank.payment.application.security.InvalidWebhookSignatureException;
import com.bank.payment.domain.exception.AccountInactiveException;
import com.bank.payment.domain.exception.AccountNotFoundException;
import com.bank.payment.domain.exception.InsufficientFundsException;
import com.bank.payment.domain.exception.InvalidTransferAmountException;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * Business metrics for dashboards and alerts.
 * Tags are low-cardinality outcomes only: never account ids, customer ids or amounts.
 */
@Component
public class PaymentMetrics {

    private final MeterRegistry registry;
    private final Counter outboxPublishFailures;
    private final Counter deadLetters;
    private final Counter duplicatesSkipped;

    public PaymentMetrics(MeterRegistry registry) {
        this.registry = registry;
        this.outboxPublishFailures = Counter.builder("mutabik.outbox.publish.failures")
                .description("Outbox events Kafka did not acknowledge (left PENDING for retry)")
                .register(registry);
        this.deadLetters = Counter.builder("mutabik.kafka.dlq.messages")
                .description("Messages that landed in the dead-letter topic")
                .register(registry);
        this.duplicatesSkipped = Counter.builder("mutabik.kafka.duplicates.skipped")
                .description("Redelivered events skipped by the idempotent consumer")
                .register(registry);

        // Pre-register every outcome at 0: Prometheus rate()/increase() ignore a series' first sample,
        // so a counter created on first use would hide the first burst after a restart from the alerts.
        TRANSFER_OUTCOMES.forEach(o -> counter(TRANSFERS, TRANSFERS_DESC, o));
        GATEWAY_OUTCOMES.forEach(o -> counter(GATEWAY, GATEWAY_DESC, o));
        WEBHOOK_OUTCOMES.forEach(o -> counter(WEBHOOKS, WEBHOOKS_DESC, o));
    }

    private static final String TRANSFERS = "mutabik.transfers";
    private static final String TRANSFERS_DESC = "Money transfers by outcome";
    private static final String GATEWAY = "mutabik.gateway.payments";
    private static final String GATEWAY_DESC = "Gateway payment authorizations by outcome";
    private static final String WEBHOOKS = "mutabik.webhooks";
    private static final String WEBHOOKS_DESC = "PSP webhooks by outcome";
    private static final java.util.List<String> TRANSFER_OUTCOMES =
            java.util.List.of("success", "replay", "insufficient_funds", "not_found", "rejected", "error");
    private static final java.util.List<String> GATEWAY_OUTCOMES =
            java.util.List.of("authorized", "not_found", "rejected", "error");
    private static final java.util.List<String> WEBHOOK_OUTCOMES =
            java.util.List.of("captured", "replay", "invalid_signature", "insufficient_funds", "rejected", "error");

    /** outcome: success | replay | insufficient_funds | not_found | rejected | error */
    public void transfer(String outcome) {
        counter(TRANSFERS, TRANSFERS_DESC, outcome).increment();
    }

    /** outcome: authorized | not_found | rejected | error */
    public void gatewayPayment(String outcome) {
        counter(GATEWAY, GATEWAY_DESC, outcome).increment();
    }

    /** outcome: captured | replay | invalid_signature | insufficient_funds | rejected | error */
    public void webhook(String outcome) {
        counter(WEBHOOKS, WEBHOOKS_DESC, outcome).increment();
    }

    public void outboxPublishFailure() { outboxPublishFailures.increment(); }

    public void deadLetter() { deadLetters.increment(); }

    public void duplicateSkipped() { duplicatesSkipped.increment(); }

    /** Business rejections get their own outcome so they never count toward the error-rate alert. */
    public static String outcomeOf(Throwable ex) {
        if (ex instanceof InsufficientFundsException) return "insufficient_funds";
        if (ex instanceof AccountNotFoundException) return "not_found";
        if (ex instanceof InvalidWebhookSignatureException) return "invalid_signature";
        if (ex instanceof AccountInactiveException
                || ex instanceof InvalidTransferAmountException
                || ex instanceof IllegalArgumentException
                || ex instanceof IllegalStateException) return "rejected";
        return "error";
    }

    private Counter counter(String name, String description, String outcome) {
        return Counter.builder(name).description(description).tag("outcome", outcome).register(registry);
    }
}
