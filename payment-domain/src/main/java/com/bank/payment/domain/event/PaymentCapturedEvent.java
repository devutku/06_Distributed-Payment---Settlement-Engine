package com.bank.payment.domain.event;

import com.bank.payment.domain.model.account.AccountId;
import com.bank.payment.domain.model.shared.Money;
import com.bank.payment.domain.model.transfer.PaymentGatewayType;
import com.bank.payment.domain.model.transfer.TransferId;

import java.time.Instant;
import java.util.UUID;

public record PaymentCapturedEvent(
        UUID eventId,
        TransferId transferId,
        String externalReferenceId,
        PaymentGatewayType gatewayType,
        AccountId sourceAccountId,
        AccountId targetAccountId,
        Money amount,
        Instant occurredAt
) implements DomainEvent {

    public PaymentCapturedEvent(TransferId transferId, String externalReferenceId, PaymentGatewayType gatewayType,
                                AccountId sourceAccountId, AccountId targetAccountId, Money amount) {
        this(UUID.randomUUID(), transferId, externalReferenceId, gatewayType, sourceAccountId, targetAccountId, amount, Instant.now());
    }

    @Override
    public UUID getEventId() {
        return eventId;
    }

    @Override
    public Instant getOccurredAt() {
        return occurredAt;
    }

    @Override
    public String getEventType() {
        return "PaymentCaptured";
    }
}

