package com.bank.payment.api.config;

import com.bank.payment.application.port.out.*;
import com.bank.payment.application.security.AccountAccessPolicy;
import com.bank.payment.application.service.GatewayPaymentWorkflowService;
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
            OutboxEventPort outboxEventPort,
            TransactionPort transactionPort) {
        return new TransferExecutionService(
                loadAccountPort,
                updateAccountBalancePort,
                saveTransferPort,
                idempotencyPort,
                outboxEventPort,
                transactionPort,
                new AccountAccessPolicy()
        );
    }

    @Bean
    public GatewayPaymentWorkflowService gatewayPaymentWorkflowService(
            LoadAccountPort loadAccountPort,
            UpdateAccountBalancePort updateAccountBalancePort,
            SaveTransferPort saveTransferPort,
            IdempotencyPort idempotencyPort,
            OutboxEventPort outboxEventPort,
            PaymentGatewayPort paymentGatewayPort) {
        return new GatewayPaymentWorkflowService(
                loadAccountPort,
                updateAccountBalancePort,
                saveTransferPort,
                idempotencyPort,
                outboxEventPort,
                paymentGatewayPort,
                new AccountAccessPolicy()
        );
    }
}
