package com.bank.payment.application.port.in;

import com.bank.payment.domain.model.account.AccountId;
import com.bank.payment.domain.model.shared.Money;
import com.bank.payment.domain.model.transfer.IdempotencyKey;
import com.bank.payment.domain.model.transfer.PaymentGatewayType;
import com.bank.payment.domain.model.transfer.TransferStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public interface InitiateGatewayPaymentUseCase {

    record GatewayPaymentCommand(
            IdempotencyKey idempotencyKey,
            AccountId customerAccountId,
            AccountId merchantAccountId,
            Money amount,
            PaymentGatewayType gatewayType,
            String customerEmail,
            String description
    ) {}

    record GatewayPaymentResponse(
            UUID transferId,
            String idempotencyKey,
            PaymentGatewayType gatewayType,
            String externalReferenceId,
            BigDecimal amount,
            String currency,
            TransferStatus status,
            String clientSecret,
            String message,
            Instant timestamp
    ) {}

    GatewayPaymentResponse initiatePayment(GatewayPaymentCommand command);
}

