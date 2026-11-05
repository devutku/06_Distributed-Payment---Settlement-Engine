package com.bank.payment.application.port.out;

import com.bank.payment.domain.model.account.Account;
import com.bank.payment.domain.model.account.AccountId;

import java.util.List;
import java.util.Optional;

public interface LoadAccountPort {
    Optional<Account> loadAccount(AccountId id);
    Optional<Account> loadAccountWithLock(AccountId id);
    List<Account> loadAllAccounts();
}

