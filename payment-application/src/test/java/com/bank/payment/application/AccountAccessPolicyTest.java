package com.bank.payment.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bank.payment.application.security.AccountAccessPolicy;
import com.bank.payment.application.security.Requester;
import com.bank.payment.domain.exception.AccountNotFoundException;
import com.bank.payment.domain.model.account.Account;
import com.bank.payment.domain.model.account.AccountId;
import com.bank.payment.domain.model.account.AccountStatus;
import com.bank.payment.domain.model.shared.Currency;
import com.bank.payment.domain.model.shared.Money;
import com.bank.payment.domain.model.transfer.IdempotencyKey;
import org.junit.jupiter.api.Test;

class AccountAccessPolicyTest {

    private final AccountAccessPolicy policy = new AccountAccessPolicy();

    private Account account(String owner) {
        return new Account(AccountId.generate(), "C-1", Money.of(100.00, Currency.TRY), AccountStatus.ACTIVE, 0L, owner);
    }

    @Test
    void customerCanMoveFundsFromOwnAccount() {
        assertThatCode(() -> policy.assertCanMoveFundsFrom(Requester.customer("CUST-ALICE"), account("CUST-ALICE")))
                .doesNotThrowAnyException();
    }

    @Test
    void customerCannotMoveFundsFromOthersAccount() {
        assertThatThrownBy(() -> policy.assertCanMoveFundsFrom(Requester.customer("CUST-ALICE"), account("CUST-BOB")))
                .isInstanceOf(AccountNotFoundException.class);
    }

    @Test
    void adminCannotMoveFunds() {
        assertThatThrownBy(() -> policy.assertCanMoveFundsFrom(Requester.admin(), account("CUST-ALICE")))
                .isInstanceOf(AccountNotFoundException.class);
    }

    @Test
    void systemCanMoveFunds() {
        assertThatCode(() -> policy.assertCanMoveFundsFrom(Requester.system(), account(null)))
                .doesNotThrowAnyException();
    }

    @Test
    void customerCanSendToOtherCustomer() {
        assertThatCode(() -> policy.assertCanReceive(Requester.customer("CUST-ALICE"), account("CUST-BOB")))
                .doesNotThrowAnyException();
    }

    @Test
    void customerCannotSendToInternalAccount() {
        assertThatThrownBy(() -> policy.assertCanReceive(Requester.customer("CUST-ALICE"), account(null)))
                .isInstanceOf(AccountNotFoundException.class);
    }

    @Test
    void customerCannotSendToInactiveOwnedAccount() {
        Account inactive = new Account(AccountId.generate(), "C-2", Money.of(100.00, Currency.TRY),
                AccountStatus.SUSPENDED, 0L, "CUST-BOB");
        assertThat(policy.canReceive(Requester.customer("CUST-ALICE"), inactive)).isFalse();
        assertThatThrownBy(() -> policy.assertCanReceive(Requester.customer("CUST-ALICE"), inactive))
                .isInstanceOf(AccountNotFoundException.class);
    }

    @Test
    void systemCanReceiveOnAnyAccount() {
        Account inactiveInternal = new Account(AccountId.generate(), "C-3", Money.of(100.00, Currency.TRY),
                AccountStatus.SUSPENDED, 0L, null);
        assertThat(policy.canReceive(Requester.system(), inactiveInternal)).isTrue();
        assertThatCode(() -> policy.assertCanReceive(Requester.system(), inactiveInternal)).doesNotThrowAnyException();
    }

    @Test
    void customerCanViewOnlyOwnAccount() {
        Requester alice = Requester.customer("CUST-ALICE");
        assertThat(policy.canView(alice, account("CUST-ALICE"))).isTrue();
        assertThat(policy.canView(alice, account("CUST-BOB"))).isFalse();
        assertThatThrownBy(() -> policy.assertCanView(alice, account("CUST-BOB")))
                .isInstanceOf(AccountNotFoundException.class);
    }

    @Test
    void adminCanViewAnyAccount() {
        assertThat(policy.canView(Requester.admin(), account("CUST-BOB"))).isTrue();
        assertThat(policy.canView(Requester.admin(), account(null))).isTrue();
    }

    @Test
    void requesterCustomerRequiresId() {
        assertThatThrownBy(() -> Requester.customer(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Requester.customer(" ")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void scopeNamespacesKeyPerRequester() {
        IdempotencyKey k = IdempotencyKey.of("k1");
        assertThat(Requester.customer("CUST-ALICE").scope(k).value()).isEqualTo("CUST-ALICE:k1");
        assertThat(Requester.customer("CUST-BOB").scope(k)).isNotEqualTo(Requester.customer("CUST-ALICE").scope(k));
        assertThat(Requester.admin().scope(k).value()).isEqualTo("admin:k1");
        assertThat(Requester.system().scope(k).value()).isEqualTo("system:k1");
    }
}
