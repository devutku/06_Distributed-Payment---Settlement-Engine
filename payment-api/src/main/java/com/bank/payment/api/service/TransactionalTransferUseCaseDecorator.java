package com.bank.payment.api.service;

import com.bank.payment.application.dto.InitiateTransferCommand;
import com.bank.payment.application.dto.TransferResultDto;
import com.bank.payment.application.port.in.InitiateTransferUseCase;
import com.bank.payment.application.service.TransferExecutionService;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Clean Architecture Transactional Decorator.
 * Keeps payment-application 100% free of Spring annotations,
 * while managing the atomic database transaction boundary here.
 */
@Service
@Primary
public class TransactionalTransferUseCaseDecorator implements InitiateTransferUseCase {

    private final TransferExecutionService delegate;

    public TransactionalTransferUseCaseDecorator(TransferExecutionService delegate) {
        this.delegate = delegate;
    }

    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public TransferResultDto execute(InitiateTransferCommand command) {
        return delegate.execute(command);
    }
}

