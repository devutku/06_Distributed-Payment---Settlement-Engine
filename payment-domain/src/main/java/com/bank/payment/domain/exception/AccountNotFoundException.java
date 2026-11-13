package com.bank.payment.domain.exception;

import com.bank.payment.domain.model.account.AccountId;

public class AccountNotFoundException extends RuntimeException {
    public AccountNotFoundException(AccountId id) {
        super("Hesap bulunamadı: " + id);
    }
}
