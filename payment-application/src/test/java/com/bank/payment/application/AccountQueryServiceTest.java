package com.bank.payment.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bank.payment.application.dto.AccountBalanceDto;
import com.bank.payment.application.dto.RecipientDto;
import com.bank.payment.application.dto.TransferSummaryDto;
import com.bank.payment.application.port.out.LoadAccountPort;
import com.bank.payment.application.port.out.LoadLedgerPort;
import com.bank.payment.application.port.out.LoadTransfersPort;
import com.bank.payment.application.security.AccountAccessPolicy;
import com.bank.payment.application.security.Requester;
import com.bank.payment.application.service.AccountQueryService;
import com.bank.payment.domain.exception.AccountNotFoundException;
import com.bank.payment.domain.model.account.Account;
import com.bank.payment.domain.model.account.AccountId;
import com.bank.payment.domain.model.account.AccountStatus;
import com.bank.payment.domain.model.shared.Currency;
import com.bank.payment.domain.model.shared.Money;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class AccountQueryServiceTest {

    private LoadAccountPort accounts;
    private LoadLedgerPort ledger;
    private LoadTransfersPort transfers;
    private AccountQueryService service;

    private Account alice;
    private Account bob;
    private Account charlie;
    private Account bobInactive;
    private Account bank;

    private Account acc(String number, String owner, AccountStatus status) {
        return new Account(AccountId.generate(), number, Money.of(100.00, Currency.TRY), status, 0L, owner);
    }

    @BeforeEach
    void setUp() {
        accounts = mock(LoadAccountPort.class);
        ledger = mock(LoadLedgerPort.class);
        transfers = mock(LoadTransfersPort.class);
        service = new AccountQueryService(accounts, ledger, transfers, new AccountAccessPolicy());

        alice = acc("TR330006100511123456780001 (Alice Smith)", "CUST-ALICE", AccountStatus.ACTIVE);
        bob = acc("TR330006100511123456780002 (Bob Jones)", "CUST-BOB", AccountStatus.ACTIVE);
        charlie = acc("TR330006100511123456780003 (Charlie Brown)", "CUST-CHARLIE", AccountStatus.ACTIVE);
        bobInactive = acc("TR330006100511123456780004 (Dora Frozen)", "CUST-DORA", AccountStatus.SUSPENDED);
        bank = acc("TR330006100511123456780009 (Bank Vault)", null, AccountStatus.ACTIVE);

        when(accounts.loadAllAccounts()).thenReturn(List.of(alice, bob, charlie, bobInactive, bank));
        for (Account a : List.of(alice, bob, charlie, bobInactive, bank)) {
            when(accounts.loadAccount(a.getId())).thenReturn(Optional.of(a));
        }
    }

    @Test
    void customerSeesOnlyOwnAccounts() {
        List<AccountBalanceDto> result = service.getAccounts(Requester.customer("CUST-ALICE"));
        assertThat(result).extracting(AccountBalanceDto::accountId).containsExactly(alice.getId().value());
    }

    @Test
    void adminSeesAllAccounts() {
        assertThat(service.getAccounts(Requester.admin())).hasSize(5);
    }

    @Test
    void customerCannotReadOthersAccountOrStatement() {
        Requester r = Requester.customer("CUST-ALICE");
        assertThatThrownBy(() -> service.getAccount(r, bob.getId())).isInstanceOf(AccountNotFoundException.class);
        assertThatThrownBy(() -> service.getStatement(r, bob.getId())).isInstanceOf(AccountNotFoundException.class);
    }

    @Test
    void missingAccountThrowsNotFound() {
        AccountId missing = AccountId.generate();
        when(accounts.loadAccount(missing)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.getAccount(Requester.admin(), missing))
                .isInstanceOf(AccountNotFoundException.class);
    }

    @Test
    void statementContainsLedgerItems() {
        when(ledger.loadEntries(alice.getId())).thenReturn(List.of());
        assertThat(service.getStatement(Requester.customer("CUST-ALICE"), alice.getId()).accountId())
                .isEqualTo(alice.getId().value());
        verify(ledger).loadEntries(alice.getId());
    }

    @Test
    @SuppressWarnings("unchecked")
    void customerRecentTransfersOnlyInvolvingOwnAccounts() {
        List<TransferSummaryDto> expected = List.of();
        when(transfers.loadRecentInvolving(any(Collection.class), anyInt())).thenReturn(expected);

        service.getRecentTransfers(Requester.customer("CUST-ALICE"));

        ArgumentCaptor<Collection<AccountId>> captor = ArgumentCaptor.forClass(Collection.class);
        verify(transfers).loadRecentInvolving(captor.capture(), org.mockito.ArgumentMatchers.eq(50));
        assertThat(captor.getValue()).containsExactly(alice.getId());
        verify(transfers, never()).loadRecent(anyInt());
    }

    @Test
    void adminRecentTransfersUsesLoadRecent() {
        service.getRecentTransfers(Requester.admin());
        verify(transfers).loadRecent(50);
    }

    @Test
    void recipientsExcludeOwnInternalAndInactive() {
        List<RecipientDto> result = service.getRecipients(Requester.customer("CUST-ALICE"));
        assertThat(result).extracting(RecipientDto::accountId)
                .containsExactlyInAnyOrder(bob.getId().value(), charlie.getId().value());
        assertThat(result).contains(new RecipientDto(bob.getId().value(), "Bob J.", "TR33 **** 0002"));
    }

    @Test
    void recipientMaskingToleratesCustomerNumberWithoutName() {
        Account nameless = acc("TR330006100511123456780005", "CUST-EVE", AccountStatus.ACTIVE);
        when(accounts.loadAllAccounts()).thenReturn(List.of(nameless));
        List<RecipientDto> result = service.getRecipients(Requester.customer("CUST-ALICE"));
        assertThat(result).containsExactly(
                new RecipientDto(nameless.getId().value(), "TR33 **** 0005", "TR33 **** 0005"));
    }
}
