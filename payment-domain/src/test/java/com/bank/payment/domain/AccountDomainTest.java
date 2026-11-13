package com.bank.payment.domain;

import com.bank.payment.domain.exception.AccountInactiveException;
import com.bank.payment.domain.exception.InsufficientFundsException;
import com.bank.payment.domain.model.account.Account;
import com.bank.payment.domain.model.account.AccountId;
import com.bank.payment.domain.model.account.AccountStatus;
import com.bank.payment.domain.model.shared.Currency;
import com.bank.payment.domain.model.shared.Money;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.*;

class AccountDomainTest {

    @Test
    @DisplayName("Debit should deduct balance correctly when sufficient funds exist")
    void shouldDebitWhenSufficientFunds() {
        AccountId id = AccountId.generate();
        Account account = new Account(id, "ACC-001", Money.of(500.00, Currency.TRY), AccountStatus.ACTIVE, 0L, "CUST-1");

        account.debit(Money.of(200.00, Currency.TRY));

        assertThat(account.getBalance().amount()).isEqualByComparingTo(new BigDecimal("300.00"));
    }

    @Test
    @DisplayName("Debit should throw InsufficientFundsException when balance is less than debit amount")
    void shouldThrowWhenInsufficientFunds() {
        AccountId id = AccountId.generate();
        Account account = new Account(id, "ACC-002", Money.of(100.00, Currency.TRY), AccountStatus.ACTIVE, 0L, "CUST-1");

        assertThatThrownBy(() -> account.debit(Money.of(150.00, Currency.TRY)))
                .isInstanceOf(InsufficientFundsException.class);
    }

    @Test
    @DisplayName("Operations should throw AccountInactiveException when account is suspended or closed")
    void shouldThrowWhenAccountInactive() {
        AccountId id = AccountId.generate();
        Account account = new Account(id, "ACC-003", Money.of(1000.00, Currency.TRY), AccountStatus.SUSPENDED, 0L, "CUST-1");

        assertThatThrownBy(() -> account.debit(Money.of(50.00, Currency.TRY)))
                .isInstanceOf(AccountInactiveException.class);
    }

    @Test
    @DisplayName("isOwnedBy matches only the owning customer")
    void isOwnedByMatchesOwner() {
        Account account = new Account(AccountId.generate(), "ACC-004", Money.of(10.00, Currency.TRY), AccountStatus.ACTIVE, 0L, "CUST-ALICE");

        assertThat(account.isOwnedBy("CUST-ALICE")).isTrue();
        assertThat(account.isOwnedBy("CUST-BOB")).isFalse();
    }

    @Test
    @DisplayName("Bank-internal account (null owner) is owned by nobody")
    void internalAccountIsOwnedByNobody() {
        Account account = new Account(AccountId.generate(), "ACC-005", Money.of(10.00, Currency.TRY), AccountStatus.ACTIVE, 0L, null);

        assertThat(account.isOwnedBy(null)).isFalse();
        assertThat(account.isOwnedBy("CUST-ALICE")).isFalse();
    }
}
