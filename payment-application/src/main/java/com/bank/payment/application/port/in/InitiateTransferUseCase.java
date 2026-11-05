package com.bank.payment.application.port.in;

import com.bank.payment.application.dto.InitiateTransferCommand;
import com.bank.payment.application.dto.TransferResultDto;

public interface InitiateTransferUseCase {
    TransferResultDto execute(InitiateTransferCommand command);
}

