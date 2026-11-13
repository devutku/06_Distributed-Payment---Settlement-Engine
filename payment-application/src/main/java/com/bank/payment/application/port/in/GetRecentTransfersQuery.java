package com.bank.payment.application.port.in;

import com.bank.payment.application.dto.TransferSummaryDto;
import com.bank.payment.application.security.Requester;

import java.util.List;

public interface GetRecentTransfersQuery {
    List<TransferSummaryDto> getRecentTransfers(Requester requester);
}
