package com.bank.payment.application.port.out;

import com.bank.payment.domain.model.shared.Money;
import com.bank.payment.domain.model.transfer.IdempotencyKey;
import com.bank.payment.domain.model.transfer.PaymentGatewayType;
import com.bank.payment.domain.model.transfer.TransferId;

public interface PaymentGatewayPort {

    record GatewayAuthorizationRequest(
            TransferId transferId,
            IdempotencyKey idempotencyKey,
            Money amount,
            PaymentGatewayType gatewayType,
            String customerEmail
    ) {}

    record GatewayAuthorizationResponse(
            boolean successful,
            String externalReferenceId, // e.g., pi_stripe_3N01923
            String clientSecret,
            String message
    ) {}

    GatewayAuthorizationResponse authorizePayment(GatewayAuthorizationRequest request);

    boolean verifySignature(String payload, String signatureHeader);
}
