package com.bank.payment.api.service;

import com.bank.payment.application.port.in.InitiateGatewayPaymentUseCase;
import com.bank.payment.application.port.in.ProcessPaymentWebhookUseCase;
import com.bank.payment.application.service.GatewayPaymentWorkflowService;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
@Primary
public class TransactionalGatewayPaymentDecorator implements InitiateGatewayPaymentUseCase, ProcessPaymentWebhookUseCase {

    private final GatewayPaymentWorkflowService delegate;

    public TransactionalGatewayPaymentDecorator(GatewayPaymentWorkflowService delegate) {
        this.delegate = delegate;
    }

    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public GatewayPaymentResponse initiatePayment(GatewayPaymentCommand command) {
        return delegate.initiatePayment(command);
    }

    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public WebhookResult processWebhook(WebhookCommand command) {
        return delegate.processWebhook(command);
    }
}

