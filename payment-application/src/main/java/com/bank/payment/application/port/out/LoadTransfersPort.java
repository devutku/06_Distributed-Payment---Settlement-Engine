package com.bank.payment.application.port.out;

import com.bank.payment.application.dto.TransferSummaryDto;
import com.bank.payment.domain.model.account.AccountId;

import java.util.Collection;
import java.util.List;

public interface LoadTransfersPort {
    List<TransferSummaryDto> loadRecent(int limit);
    List<TransferSummaryDto> loadRecentInvolving(Collection<AccountId> ids, int limit);
}
