package com.bank.payment.domain.model.transfer;

import java.util.Objects;

public record IdempotencyKey(String value) {

    public IdempotencyKey {
        Objects.requireNonNull(value, "IdempotencyKey value null olamaz");
        if (value.isBlank()) {
            throw new IllegalArgumentException("IdempotencyKey boş olamaz");
        }
    }

    public static IdempotencyKey of(String value) {
        return new IdempotencyKey(value);
    }

    @Override
    public String toString() {
        return value;
    }
}
