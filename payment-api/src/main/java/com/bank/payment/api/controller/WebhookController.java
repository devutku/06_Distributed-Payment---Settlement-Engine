package com.bank.payment.api.controller;

import com.bank.payment.application.port.in.ProcessPaymentWebhookUseCase;
import com.bank.payment.infrastructure.observability.PaymentMetrics;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.Instant;

@RestController
@RequestMapping("/api/v1/webhooks/gateway")
public class WebhookController {

    private final ProcessPaymentWebhookUseCase processPaymentWebhookUseCase;
    private final ObjectMapper objectMapper;
    private final PaymentMetrics metrics;

    public WebhookController(
            ProcessPaymentWebhookUseCase processPaymentWebhookUseCase,
            ObjectMapper objectMapper,
            PaymentMetrics metrics) {
        this.processPaymentWebhookUseCase = processPaymentWebhookUseCase;
        this.objectMapper = objectMapper;
        this.metrics = metrics;
    }

    public record WebhookPayload(
            String eventType, // e.g. "payment_intent.succeeded" or "charge.captured"
            String externalReferenceId,
            BigDecimal amount,
            String currency,
            Instant timestamp
    ) {}

    /**
     * Webhook Listener (Step 5 in High-Level Architecture Diagram).
     * Protected with HMAC-SHA256 signature verification.
     */
    @PostMapping
    public ResponseEntity<ProcessPaymentWebhookUseCase.WebhookResult> receiveWebhook(
            @RequestHeader(value = "X-Signature", required = false) String signatureHeader,
            @RequestBody String rawPayload) {

        try {
            WebhookPayload payload = objectMapper.readValue(rawPayload, WebhookPayload.class);

            ProcessPaymentWebhookUseCase.WebhookCommand command = new ProcessPaymentWebhookUseCase.WebhookCommand(
                    rawPayload,
                    signatureHeader,
                    payload.eventType(),
                    payload.externalReferenceId(),
                    payload.amount(),
                    payload.currency()
            );

            ProcessPaymentWebhookUseCase.WebhookResult result = processPaymentWebhookUseCase.processWebhook(command);
            metrics.webhook(ProcessPaymentWebhookUseCase.REPLAY_MESSAGE.equals(result.message()) ? "replay" : "captured");
            return ResponseEntity.ok(result);
        } catch (JsonProcessingException e) {
            metrics.webhook("rejected");
            throw new IllegalArgumentException("Geçersiz webhook payload formatı: " + e.getMessage());
        } catch (RuntimeException ex) {
            metrics.webhook(PaymentMetrics.outcomeOf(ex));
            throw ex;
        }
    }
}
