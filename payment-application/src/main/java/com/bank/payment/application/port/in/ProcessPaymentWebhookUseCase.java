package com.bank.payment.application.port.in;

import com.bank.payment.domain.model.transfer.TransferStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public interface ProcessPaymentWebhookUseCase {

    record WebhookCommand(
            String payload,
            String signatureHeader,
            String eventType, // e.g. "payment_intent.succeeded" or "charge.captured"
            String externalReferenceId, // pi_stripe_xxx
            BigDecimal amount,
            String currency
    ) {}

    record WebhookResult(
            boolean processed,
            UUID transferId,
            String externalReferenceId,
            TransferStatus status,
            String message,
            Instant processedAt
    ) {}

    WebhookResult processWebhook(WebhookCommand command);
}

