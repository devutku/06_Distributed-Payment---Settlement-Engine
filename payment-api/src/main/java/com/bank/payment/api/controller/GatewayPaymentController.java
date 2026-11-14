package com.bank.payment.api.controller;

import com.bank.payment.api.filter.IdempotencyFilter;
import com.bank.payment.api.security.RequesterResolver;
import com.bank.payment.infrastructure.observability.PaymentMetrics;
import com.bank.payment.application.port.in.InitiateGatewayPaymentUseCase;
import com.bank.payment.domain.model.account.AccountId;
import com.bank.payment.domain.model.shared.Currency;
import com.bank.payment.domain.model.shared.Money;
import com.bank.payment.domain.model.transfer.IdempotencyKey;
import com.bank.payment.domain.model.transfer.PaymentGatewayType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/gateway")
public class GatewayPaymentController {

    private final InitiateGatewayPaymentUseCase initiateGatewayPaymentUseCase;

    private final RequesterResolver requesterResolver;
    private final PaymentMetrics metrics;

    public GatewayPaymentController(InitiateGatewayPaymentUseCase initiateGatewayPaymentUseCase,
                                    RequesterResolver requesterResolver,
                                    PaymentMetrics metrics) {
        this.initiateGatewayPaymentUseCase = initiateGatewayPaymentUseCase;
        this.requesterResolver = requesterResolver;
        this.metrics = metrics;
    }

    public record GatewayPayRequest(
            @NotNull(message = "customerAccountId zorunludur") UUID customerAccountId,
            @NotNull(message = "merchantAccountId zorunludur") UUID merchantAccountId,
            @NotNull(message = "amount zorunludur") @DecimalMin(value = "0.01", message = "Tutar pozitif olmalıdır") BigDecimal amount,
            Currency currency,
            PaymentGatewayType gatewayType,
            String customerEmail,
            String description
    ) {}

    @PostMapping("/pay")
    public ResponseEntity<InitiateGatewayPaymentUseCase.GatewayPaymentResponse> pay(
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKeyHeader,
            @Valid @RequestBody GatewayPayRequest req,
            Authentication auth) {

        if (idempotencyKeyHeader != null && idempotencyKeyHeader.length() > IdempotencyFilter.MAX_KEY_LENGTH) {
            throw new IllegalArgumentException("'Idempotency-Key' en fazla 100 karakter olabilir.");
        }
        String key = idempotencyKeyHeader != null ? idempotencyKeyHeader : UUID.randomUUID().toString();
        Currency curr = req.currency() != null ? req.currency() : Currency.TRY;
        PaymentGatewayType gwType = req.gatewayType() != null ? req.gatewayType() : PaymentGatewayType.STRIPE;

        InitiateGatewayPaymentUseCase.GatewayPaymentCommand command = new InitiateGatewayPaymentUseCase.GatewayPaymentCommand(
                IdempotencyKey.of(key),
                AccountId.of(req.customerAccountId()),
                AccountId.of(req.merchantAccountId()),
                Money.of(req.amount(), curr),
                gwType,
                req.customerEmail() != null ? req.customerEmail() : "customer@example.com",
                req.description() != null ? req.description() : "E-Commerce Checkout",
                requesterResolver.resolve(auth)
        );

        try {
            InitiateGatewayPaymentUseCase.GatewayPaymentResponse response = initiateGatewayPaymentUseCase.initiatePayment(command);
            metrics.gatewayPayment("authorized");
            return ResponseEntity.ok(response);
        } catch (RuntimeException ex) {
            metrics.gatewayPayment(PaymentMetrics.outcomeOf(ex));
            throw ex;
        }
    }
}

