package com.bank.payment.application.port.out;

import com.bank.payment.domain.model.account.Account;

public interface UpdateAccountBalancePort {
    void updateAccount(Account account);
}

