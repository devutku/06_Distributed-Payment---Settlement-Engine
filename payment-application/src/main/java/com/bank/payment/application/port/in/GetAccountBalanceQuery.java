package com.bank.payment.application.port.in;

import com.bank.payment.application.dto.AccountBalanceDto;
import com.bank.payment.domain.model.account.AccountId;

import java.util.List;

public interface GetAccountBalanceQuery {
    AccountBalanceDto getAccountById(AccountId accountId);
    List<AccountBalanceDto> getAllAccounts();
}
