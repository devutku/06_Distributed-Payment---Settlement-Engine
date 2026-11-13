package com.bank.payment.application.port.in;

import com.bank.payment.application.dto.AccountBalanceDto;
import com.bank.payment.application.security.Requester;
import com.bank.payment.domain.model.account.AccountId;

import java.util.List;

public interface GetAccountBalanceQuery {
    List<AccountBalanceDto> getAccounts(Requester requester);
    AccountBalanceDto getAccount(Requester requester, AccountId accountId);
}
