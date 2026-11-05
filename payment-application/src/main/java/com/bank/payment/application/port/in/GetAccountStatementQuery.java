package com.bank.payment.application.port.in;

import com.bank.payment.application.dto.AccountStatementDto;
import com.bank.payment.domain.model.account.AccountId;

public interface GetAccountStatementQuery {
    AccountStatementDto getStatement(AccountId accountId);
}
