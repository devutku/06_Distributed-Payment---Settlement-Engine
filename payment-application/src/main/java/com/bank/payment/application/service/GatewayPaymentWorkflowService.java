package com.bank.payment.application.service;

import com.bank.payment.application.port.in.InitiateGatewayPaymentUseCase;
import com.bank.payment.application.port.in.ProcessPaymentWebhookUseCase;
import com.bank.payment.application.port.out.*;
import com.bank.payment.domain.event.PaymentCapturedEvent;
import com.bank.payment.domain.model.account.Account;
import com.bank.payment.domain.model.account.AccountId;
import com.bank.payment.domain.model.transfer.Transfer;
import com.bank.payment.domain.model.transfer.TransferId;
import com.bank.payment.domain.model.transfer.TransferStatus;

import java.time.Instant;
import java.util.Objects;

public class GatewayPaymentWorkflowService implements InitiateGatewayPaymentUseCase, ProcessPaymentWebhookUseCase {

    private final LoadAccountPort loadAccountPort;
    private final UpdateAccountBalancePort updateAccountBalancePort;
    private final SaveTransferPort saveTransferPort;
    private final IdempotencyPort idempotencyPort;
    private final OutboxEventPort outboxEventPort;
    private final PaymentGatewayPort paymentGatewayPort;

    public GatewayPaymentWorkflowService(
            LoadAccountPort loadAccountPort,
            UpdateAccountBalancePort updateAccountBalancePort,
            SaveTransferPort saveTransferPort,
            IdempotencyPort idempotencyPort,
            OutboxEventPort outboxEventPort,
            PaymentGatewayPort paymentGatewayPort) {
        this.loadAccountPort = Objects.requireNonNull(loadAccountPort);
        this.updateAccountBalancePort = Objects.requireNonNull(updateAccountBalancePort);
        this.saveTransferPort = Objects.requireNonNull(saveTransferPort);
        this.idempotencyPort = Objects.requireNonNull(idempotencyPort);
        this.outboxEventPort = Objects.requireNonNull(outboxEventPort);
        this.paymentGatewayPort = Objects.requireNonNull(paymentGatewayPort);
    }

    @Override
    public GatewayPaymentResponse initiatePayment(GatewayPaymentCommand command) {
        // Step 1: Idempotency in-flight lock
        boolean acquired = idempotencyPort.acquireLock(command.idempotencyKey());
        if (!acquired) {
            throw new IllegalStateException("Aynı Idempotency-Key ile devam eden bir işlem mevcut: " + command.idempotencyKey());
        }

        try {
            // Step 2: Create PENDING Record in SQL Database
            Transfer transfer = new Transfer(
                    TransferId.generate(),
                    command.idempotencyKey(),
                    command.customerAccountId(),
                    command.merchantAccountId(),
                    command.amount(),
                    command.gatewayType()
            );

            // Step 3: Call Payment Gateway (Stripe / PayPal / Razorpay)
            PaymentGatewayPort.GatewayAuthorizationRequest gatewayRequest = new PaymentGatewayPort.GatewayAuthorizationRequest(
                    transfer.getId(),
                    transfer.getIdempotencyKey(),
                    transfer.getAmount(),
                    command.gatewayType(),
                    command.customerEmail()
            );

            PaymentGatewayPort.GatewayAuthorizationResponse gatewayResponse = paymentGatewayPort.authorizePayment(gatewayRequest);

            if (!gatewayResponse.successful()) {
                transfer.markAsRejected(gatewayResponse.message());
                saveTransferPort.saveTransfer(transfer);
                throw new IllegalStateException("Ödeme geçidi yetkilendirmesi başarısız: " + gatewayResponse.message());
            }

            // Step 4: Mark AUTHORIZED with external reference
            transfer.authorize(gatewayResponse.externalReferenceId());
            saveTransferPort.saveTransfer(transfer);

            return new GatewayPaymentResponse(
                    transfer.getId().value(),
                    command.idempotencyKey().value(),
                    command.gatewayType(),
                    gatewayResponse.externalReferenceId(),
                    transfer.getAmount().amount(),
                    transfer.getAmount().currency().name(),
                    transfer.getStatus(),
                    gatewayResponse.clientSecret(),
                    "Ödeme sağlayıcısı yetkilendirmesi (AUTHORIZED) başarıyla alındı. Webhook bekleniyor.",
                    Instant.now()
            );
        } finally {
            idempotencyPort.releaseLock(command.idempotencyKey());
        }
    }

    @Override
    public WebhookResult processWebhook(WebhookCommand command) {
        // Step 5: Verify Webhook Signature (HMAC-SHA256)
        boolean validSignature = paymentGatewayPort.verifySignature(command.payload(), command.signatureHeader());
        if (!validSignature) {
            throw new SecurityException("Webhook HMAC imza doğrulaması başarısız! Sahte webhook isteği engellendi.");
        }

        // Load Transfer by external reference ID
        Transfer transfer = saveTransferPort.findByExternalReferenceId(command.externalReferenceId())
                .orElseThrow(() -> new IllegalArgumentException("İşlem referansı bulunamadı: " + command.externalReferenceId()));

        if (transfer.getStatus() == TransferStatus.SETTLED || transfer.getStatus() == TransferStatus.CAPTURED) {
            return new WebhookResult(
                    true,
                    transfer.getId().value(),
                    transfer.getExternalReferenceId(),
                    transfer.getStatus(),
                    "Webhook daha önce işlenmiş (Idempotent Webhook Replay)",
                    Instant.now()
            );
        }

        // Step 6: Mark as CAPTURED & SETTLED
        transfer.capture();
        transfer.markAsSettled();

        // Step 7: Deterministic Locking Order on Accounts & Balance Adjustment
        AccountId customerId = transfer.getSourceAccountId();
        AccountId merchantId = transfer.getTargetAccountId();

        AccountId firstId = customerId.compareTo(merchantId) < 0 ? customerId : merchantId;
        AccountId secondId = customerId.compareTo(merchantId) < 0 ? merchantId : customerId;

        Account firstLocked = loadAccountPort.loadAccountWithLock(firstId)
                .orElseThrow(() -> new IllegalArgumentException("Hesap bulunamadı: " + firstId));
        Account secondLocked = loadAccountPort.loadAccountWithLock(secondId)
                .orElseThrow(() -> new IllegalArgumentException("Hesap bulunamadı: " + secondId));

        Account customerAccount = customerId.equals(firstId) ? firstLocked : secondLocked;
        Account merchantAccount = merchantId.equals(firstId) ? firstLocked : secondLocked;

        customerAccount.debit(transfer.getAmount());
        merchantAccount.credit(transfer.getAmount());

        updateAccountBalancePort.updateAccount(customerAccount);
        updateAccountBalancePort.updateAccount(merchantAccount);
        saveTransferPort.saveTransfer(transfer);

        // Step 8: Update Ledger (Record AUTHORIZED / CAPTURED)
        saveTransferPort.recordDoubleEntryLedger(
                transfer, customerAccount, merchantAccount,
                "PSP Captured: " + transfer.getGatewayType() + " [" + transfer.getExternalReferenceId() + "]"
        );

        // Step 9: Trigger Order Fulfillment (Transactional Outbox -> Kafka)
        PaymentCapturedEvent event = new PaymentCapturedEvent(
                transfer.getId(),
                transfer.getExternalReferenceId(),
                transfer.getGatewayType(),
                customerAccount.getId(),
                merchantAccount.getId(),
                transfer.getAmount()
        );
        outboxEventPort.saveEvent(event);

        return new WebhookResult(
                true,
                transfer.getId().value(),
                transfer.getExternalReferenceId(),
                transfer.getStatus(),
                "Ödeme başarıyla CAPTURED yapıldı. Bakiye ve Ledger güncellendi, Order Fulfillment tetiklendi.",
                Instant.now()
        );
    }
}

