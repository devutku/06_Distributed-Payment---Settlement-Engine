package com.bank.payment.application.dto;

import com.bank.payment.domain.model.account.AccountId;
import com.bank.payment.domain.model.shared.Currency;
import com.bank.payment.domain.model.shared.Money;
import com.bank.payment.domain.model.transfer.IdempotencyKey;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.UUID;

public record InitiateTransferCommand(
        IdempotencyKey idempotencyKey,
        AccountId sourceAccountId,
        AccountId targetAccountId,
        Money amount,
        String description
) {
    public InitiateTransferCommand {
        Objects.requireNonNull(idempotencyKey, "idempotencyKey null olamaz");
        Objects.requireNonNull(sourceAccountId, "sourceAccountId null olamaz");
        Objects.requireNonNull(targetAccountId, "targetAccountId null olamaz");
        Objects.requireNonNull(amount, "amount null olamaz");
        if (sourceAccountId.equals(targetAccountId)) {
            throw new IllegalArgumentException("Kaynak ve hedef hesap aynı olamaz");
        }
    }

    public static InitiateTransferCommand of(
            String idempotencyKey, UUID sourceAccountId, UUID targetAccountId, BigDecimal amount, Currency currency, String description) {
        return new InitiateTransferCommand(
                IdempotencyKey.of(idempotencyKey),
                AccountId.of(sourceAccountId),
                AccountId.of(targetAccountId),
                Money.of(amount, currency),
                description != null ? description : "Payment Transfer"
        );
    }
}

