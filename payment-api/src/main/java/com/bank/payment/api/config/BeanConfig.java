package com.bank.payment.api.config;

import com.bank.payment.application.port.out.*;
import com.bank.payment.application.service.TransferExecutionService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class BeanConfig {

    @Bean
    public TransferExecutionService transferExecutionService(
            LoadAccountPort loadAccountPort,
            UpdateAccountBalancePort updateAccountBalancePort,
            SaveTransferPort saveTransferPort,
            IdempotencyPort idempotencyPort,
            OutboxEventPort outboxEventPort) {
        return new TransferExecutionService(
                loadAccountPort,
                updateAccountBalancePort,
                saveTransferPort,
                idempotencyPort,
                outboxEventPort
        );
    }
}

