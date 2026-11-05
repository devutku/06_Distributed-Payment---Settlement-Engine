package com.bank.payment.api.controller;

import com.bank.payment.application.port.in.ProcessPaymentWebhookUseCase;
import com.bank.payment.infrastructure.gateway.MockPaymentGatewayAdapter;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/webhooks/gateway")
public class WebhookController {

    private final ProcessPaymentWebhookUseCase processPaymentWebhookUseCase;
    private final MockPaymentGatewayAdapter gatewayAdapter;
    private final ObjectMapper objectMapper;

    public WebhookController(
            ProcessPaymentWebhookUseCase processPaymentWebhookUseCase,
            MockPaymentGatewayAdapter gatewayAdapter,
            ObjectMapper objectMapper) {
        this.processPaymentWebhookUseCase = processPaymentWebhookUseCase;
        this.gatewayAdapter = gatewayAdapter;
        this.objectMapper = objectMapper;
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
            return ResponseEntity.ok(result);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Geçersiz webhook payload formatı: " + e.getMessage());
        }
    }

    /**
     * Demo / Interview Helper: Automatically signs and fires a CAPTURED webhook for an external reference.
     */
    @PostMapping("/simulate")
    public ResponseEntity<ProcessPaymentWebhookUseCase.WebhookResult> simulateWebhook(
            @RequestBody Map<String, Object> req) throws JsonProcessingException {

        String externalRef = (String) req.get("externalReferenceId");
        BigDecimal amount = req.containsKey("amount") ? new BigDecimal(req.get("amount").toString()) : new BigDecimal("250.00");
        String currency = req.containsKey("currency") ? (String) req.get("currency") : "TRY";

        WebhookPayload payload = new WebhookPayload(
                "payment_intent.succeeded",
                externalRef,
                amount,
                currency,
                Instant.now()
        );

        String json = objectMapper.writeValueAsString(payload);
        String signature = gatewayAdapter.computeHmacSha256(json, gatewayAdapter.getWebhookSecret());

        ProcessPaymentWebhookUseCase.WebhookCommand command = new ProcessPaymentWebhookUseCase.WebhookCommand(
                json,
                signature,
                payload.eventType(),
                payload.externalReferenceId(),
                payload.amount(),
                payload.currency()
        );

        return ResponseEntity.ok(processPaymentWebhookUseCase.processWebhook(command));
    }
}
