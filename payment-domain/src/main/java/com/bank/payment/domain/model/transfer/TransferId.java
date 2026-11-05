package com.bank.payment.domain.model.transfer;

import java.util.Objects;
import java.util.UUID;

public record TransferId(UUID value) {

    public TransferId {
        Objects.requireNonNull(value, "TransferId value null olamaz");
    }

    public static TransferId of(UUID value) {
        return new TransferId(value);
    }

    public static TransferId of(String value) {
        return new TransferId(UUID.fromString(value));
    }

    public static TransferId generate() {
        return new TransferId(UUID.randomUUID());
    }

    @Override
    public String toString() {
        return value.toString();
    }
}

