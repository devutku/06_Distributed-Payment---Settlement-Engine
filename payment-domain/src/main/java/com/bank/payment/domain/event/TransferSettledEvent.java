package com.bank.payment.domain.event;

import com.bank.payment.domain.model.account.AccountId;
import com.bank.payment.domain.model.shared.Money;
import com.bank.payment.domain.model.transfer.IdempotencyKey;
import com.bank.payment.domain.model.transfer.TransferId;

import java.time.Instant;
import java.util.UUID;

public record TransferSettledEvent(
        UUID eventId,
        TransferId transferId,
        IdempotencyKey idempotencyKey,
        AccountId sourceAccountId,
        AccountId targetAccountId,
        Money amount,
        Instant occurredAt
) implements DomainEvent {

    public TransferSettledEvent(TransferId transferId, IdempotencyKey idempotencyKey,
                                AccountId sourceAccountId, AccountId targetAccountId, Money amount) {
        this(UUID.randomUUID(), transferId, idempotencyKey, sourceAccountId, targetAccountId, amount, Instant.now());
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
        return "TransferSettled";
    }
}
