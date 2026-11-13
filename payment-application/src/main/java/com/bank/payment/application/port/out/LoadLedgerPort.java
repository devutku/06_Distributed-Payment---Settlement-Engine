package com.bank.payment.application.port.out;

import com.bank.payment.application.dto.AccountStatementDto;
import com.bank.payment.domain.model.account.AccountId;

import java.util.List;

public interface LoadLedgerPort {
    /** Ledger entries of the account, newest first. */
    List<AccountStatementDto.LedgerItem> loadEntries(AccountId id);
}
