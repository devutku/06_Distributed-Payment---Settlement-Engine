package com.bank.payment.application.security;

import com.bank.payment.domain.model.transfer.IdempotencyKey;

public record Requester(Kind kind, String customerId) {

    public enum Kind { CUSTOMER, ADMIN, SYSTEM }

    public static Requester customer(String customerId) {
        if (customerId == null || customerId.isBlank()) {
            throw new IllegalArgumentException("customerId bos olamaz");
        }
        return new Requester(Kind.CUSTOMER, customerId);
    }

    public static Requester admin() {
        return new Requester(Kind.ADMIN, null);
    }

    public static Requester system() {
        return new Requester(Kind.SYSTEM, null);
    }

    public boolean isCustomer() { return kind == Kind.CUSTOMER; }
    public boolean isAdmin() { return kind == Kind.ADMIN; }
    public boolean isSystem() { return kind == Kind.SYSTEM; }

    public IdempotencyKey scope(IdempotencyKey raw) {
        String prefix = switch (kind) {
            case CUSTOMER -> customerId;
            case ADMIN -> "admin";
            case SYSTEM -> "system";
        };
        return IdempotencyKey.of(prefix + ":" + raw.value());
    }
}
