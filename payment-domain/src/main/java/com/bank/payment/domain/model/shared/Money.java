package com.bank.payment.domain.model.shared;

import com.bank.payment.domain.exception.InvalidTransferAmountException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

public record Money(BigDecimal amount, Currency currency) {

    public Money {
        Objects.requireNonNull(amount, "Tutar null olamaz");
        Objects.requireNonNull(currency, "Para birimi null olamaz");
        if (amount.compareTo(BigDecimal.ZERO) < 0) {
            throw new InvalidTransferAmountException("Bakiye veya tutar negatif olamaz");
        }
        amount = amount.setScale(2, RoundingMode.HALF_EVEN); // Finans standardı (Banker's rounding)
    }

    public static Money of(double amount, Currency currency) {
        return new Money(BigDecimal.valueOf(amount), currency);
    }

    public static Money of(BigDecimal amount, Currency currency) {
        return new Money(amount, currency);
    }

    public static Money zero(Currency currency) {
        return new Money(BigDecimal.ZERO, currency);
    }

    public Money add(Money other) {
        validateCurrency(other);
        return new Money(this.amount.add(other.amount), this.currency);
    }

    public Money subtract(Money other) {
        validateCurrency(other);
        if (this.isLessThan(other)) {
            throw new InvalidTransferAmountException("Yetersiz bakiye");
        }
        return new Money(this.amount.subtract(other.amount), this.currency);
    }

    public boolean isLessThan(Money other) {
        validateCurrency(other);
        return this.amount.compareTo(other.amount) < 0;
    }

    private void validateCurrency(Money other) {
        if (this.currency != other.currency) {
            throw new IllegalArgumentException("Para birimleri eşleşmiyor: " + this.currency + " != " + other.currency);
        }
    }
}
