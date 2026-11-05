package com.bank.payment.api.controller;

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
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/gateway")
public class GatewayPaymentController {

    private final InitiateGatewayPaymentUseCase initiateGatewayPaymentUseCase;

    public GatewayPaymentController(InitiateGatewayPaymentUseCase initiateGatewayPaymentUseCase) {
        this.initiateGatewayPaymentUseCase = initiateGatewayPaymentUseCase;
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
            @Valid @RequestBody GatewayPayRequest req) {

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
                req.description() != null ? req.description() : "E-Commerce Checkout"
        );

        InitiateGatewayPaymentUseCase.GatewayPaymentResponse response = initiateGatewayPaymentUseCase.initiatePayment(command);
        return ResponseEntity.ok(response);
    }
}
