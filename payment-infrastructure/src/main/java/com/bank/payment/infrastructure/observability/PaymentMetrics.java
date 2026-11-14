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
    }

    /** outcome: success | replay | insufficient_funds | not_found | rejected | error */
    public void transfer(String outcome) {
        outcome("mutabik.transfers", "Money transfers by outcome", outcome);
    }

    /** outcome: authorized | not_found | rejected | error */
    public void gatewayPayment(String outcome) {
        outcome("mutabik.gateway.payments", "Gateway payment authorizations by outcome", outcome);
    }

    /** outcome: captured | replay | invalid_signature | not_found | rejected | error */
    public void webhook(String outcome) {
        outcome("mutabik.webhooks", "PSP webhooks by outcome", outcome);
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

    private void outcome(String name, String description, String outcome) {
        Counter.builder(name).description(description).tag("outcome", outcome).register(registry).increment();
    }
}
