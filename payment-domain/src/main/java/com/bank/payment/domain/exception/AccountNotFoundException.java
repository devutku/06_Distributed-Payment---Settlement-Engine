package com.bank.payment.domain.exception;

import com.bank.payment.domain.model.account.AccountId;

public class AccountNotFoundException extends RuntimeException {
    private final AccountId accountId;

    /** The message is constant on purpose: it must never reveal whether an account exists. */
    public AccountNotFoundException(AccountId id) {
        super("Hesap bulunamadı");
        this.accountId = id;
    }

    /** For server-side logging only; never put this in a response. */
    public AccountId getAccountId() {
        return accountId;
    }
}
