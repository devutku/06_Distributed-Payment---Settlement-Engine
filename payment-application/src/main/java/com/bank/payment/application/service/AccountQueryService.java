package com.bank.payment.application.service;

import com.bank.payment.application.dto.AccountBalanceDto;
import com.bank.payment.application.dto.AccountStatementDto;
import com.bank.payment.application.dto.RecipientDto;
import com.bank.payment.application.dto.TransferSummaryDto;
import com.bank.payment.application.port.in.GetAccountBalanceQuery;
import com.bank.payment.application.port.in.GetAccountStatementQuery;
import com.bank.payment.application.port.in.GetRecentTransfersQuery;
import com.bank.payment.application.port.in.GetRecipientsQuery;
import com.bank.payment.application.port.out.LoadAccountPort;
import com.bank.payment.application.port.out.LoadLedgerPort;
import com.bank.payment.application.port.out.LoadTransfersPort;
import com.bank.payment.application.security.AccountAccessPolicy;
import com.bank.payment.application.security.Requester;
import com.bank.payment.domain.exception.AccountNotFoundException;
import com.bank.payment.domain.model.account.Account;
import com.bank.payment.domain.model.account.AccountId;

import java.util.List;
import java.util.Objects;

public class AccountQueryService implements GetAccountBalanceQuery, GetAccountStatementQuery,
        GetRecentTransfersQuery, GetRecipientsQuery {

    private static final int RECENT_TRANSFER_LIMIT = 50;

    private final LoadAccountPort loadAccountPort;
    private final LoadLedgerPort loadLedgerPort;
    private final LoadTransfersPort loadTransfersPort;
    private final AccountAccessPolicy accessPolicy;

    public AccountQueryService(LoadAccountPort loadAccountPort,
                               LoadLedgerPort loadLedgerPort,
                               LoadTransfersPort loadTransfersPort,
                               AccountAccessPolicy accessPolicy) {
        this.loadAccountPort = Objects.requireNonNull(loadAccountPort);
        this.loadLedgerPort = Objects.requireNonNull(loadLedgerPort);
        this.loadTransfersPort = Objects.requireNonNull(loadTransfersPort);
        this.accessPolicy = Objects.requireNonNull(accessPolicy);
    }

    @Override
    public List<AccountBalanceDto> getAccounts(Requester requester) {
        return visibleAccounts(requester).stream().map(this::toBalanceDto).toList();
    }

    @Override
    public AccountBalanceDto getAccount(Requester requester, AccountId accountId) {
        return toBalanceDto(loadViewable(requester, accountId));
    }

    @Override
    public AccountStatementDto getStatement(Requester requester, AccountId accountId) {
        Account account = loadViewable(requester, accountId);
        return new AccountStatementDto(
                account.getId().value(),
                account.getCustomerNumber(),
                account.getBalance().amount(),
                account.getBalance().currency().name(),
                loadLedgerPort.loadEntries(accountId)
        );
    }

    @Override
    public List<TransferSummaryDto> getRecentTransfers(Requester requester) {
        if (requester.isCustomer()) {
            List<AccountId> owned = visibleAccounts(requester).stream().map(Account::getId).toList();
            if (owned.isEmpty()) {
                return List.of();
            }
            return loadTransfersPort.loadRecentInvolving(owned, RECENT_TRANSFER_LIMIT);
        }
        return loadTransfersPort.loadRecent(RECENT_TRANSFER_LIMIT);
    }

    @Override
    public List<RecipientDto> getRecipients(Requester requester) {
        return loadAccountPort.loadAllAccounts().stream()
                .filter(a -> accessPolicy.canReceive(requester, a))
                .filter(a -> !(requester.isCustomer() && a.isOwnedBy(requester.customerId())))
                .map(this::toRecipientDto)
                .toList();
    }

    private Account loadViewable(Requester requester, AccountId accountId) {
        Account account = loadAccountPort.loadAccount(accountId)
                .orElseThrow(() -> new AccountNotFoundException(accountId));
        accessPolicy.assertCanView(requester, account);
        return account;
    }

    private List<Account> visibleAccounts(Requester requester) {
        return loadAccountPort.loadAllAccounts().stream()
                .filter(a -> accessPolicy.canView(requester, a))
                .toList();
    }

    private AccountBalanceDto toBalanceDto(Account account) {
        return new AccountBalanceDto(
                account.getId().value(),
                account.getCustomerNumber(),
                account.getBalance().amount(),
                account.getBalance().currency().name(),
                account.getStatus(),
                account.getVersion()
        );
    }

    /** customerNumber looks like "IBAN (Full Name)"; the name part is optional. */
    private RecipientDto toRecipientDto(Account account) {
        String raw = account.getCustomerNumber() == null ? "" : account.getCustomerNumber().trim();
        String iban = raw;
        String name = null;
        int open = raw.indexOf('(');
        if (open >= 0) {
            iban = raw.substring(0, open).trim();
            int close = raw.lastIndexOf(')');
            name = raw.substring(open + 1, close > open ? close : raw.length()).trim();
        }
        String maskedIban = maskIban(iban);
        String displayName = maskName(name);
        return new RecipientDto(account.getId().value(), displayName != null ? displayName : maskedIban, maskedIban);
    }

    private static String maskIban(String iban) {
        String compact = iban.replace(" ", "");
        if (compact.length() < 8) {
            return "****";
        }
        return compact.substring(0, 4) + " **** " + compact.substring(compact.length() - 4);
    }

    /** "Bob Jones" becomes "Bob J."; a single token stays as is; blank gives null. */
    private static String maskName(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        String[] parts = name.trim().split("\\s+");
        if (parts.length == 1) {
            return parts[0];
        }
        return parts[0] + " " + Character.toUpperCase(parts[parts.length - 1].charAt(0)) + ".";
    }
}
