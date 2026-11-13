package com.bank.payment.application.dto;

import com.bank.payment.application.security.Requester;
import com.bank.payment.domain.model.account.AccountId;
import com.bank.payment.domain.model.shared.Money;
import com.bank.payment.domain.model.transfer.IdempotencyKey;

import java.util.Objects;

public record InitiateTransferCommand(
        IdempotencyKey idempotencyKey,
        AccountId sourceAccountId,
        AccountId targetAccountId,
        Money amount,
        String description,
        Requester requester
) {
    public InitiateTransferCommand {
        Objects.requireNonNull(idempotencyKey, "idempotencyKey null olamaz");
        Objects.requireNonNull(sourceAccountId, "sourceAccountId null olamaz");
        Objects.requireNonNull(targetAccountId, "targetAccountId null olamaz");
        Objects.requireNonNull(amount, "amount null olamaz");
        Objects.requireNonNull(requester, "requester null olamaz");
        if (sourceAccountId.equals(targetAccountId)) {
            throw new IllegalArgumentException("Kaynak ve hedef hesap aynı olamaz");
        }
    }
}
