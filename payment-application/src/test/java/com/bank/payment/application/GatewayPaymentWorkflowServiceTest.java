package com.bank.payment.application;

import com.bank.payment.application.port.in.ProcessPaymentWebhookUseCase.WebhookCommand;
import com.bank.payment.application.port.in.ProcessPaymentWebhookUseCase.WebhookResult;
import com.bank.payment.application.port.in.InitiateGatewayPaymentUseCase.GatewayPaymentCommand;
import com.bank.payment.application.port.in.InitiateGatewayPaymentUseCase.GatewayPaymentResponse;
import com.bank.payment.application.port.out.*;
import com.bank.payment.application.security.AccountAccessPolicy;
import com.bank.payment.application.security.Requester;
import com.bank.payment.domain.exception.AccountNotFoundException;
import com.bank.payment.application.service.GatewayPaymentWorkflowService;
import com.bank.payment.domain.model.account.Account;
import com.bank.payment.domain.model.account.AccountId;
import com.bank.payment.domain.model.account.AccountStatus;
import com.bank.payment.domain.model.shared.Currency;
import com.bank.payment.domain.model.shared.Money;
import com.bank.payment.domain.model.transfer.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class GatewayPaymentWorkflowServiceTest {

    private static final String EXTERNAL_REF = "pi_stripe_test123";
    private static final AccountId CUSTOMER_ID = AccountId.of(UUID.fromString("00000000-0000-0000-0000-000000000001"));
    private static final AccountId MERCHANT_ID = AccountId.of(UUID.fromString("00000000-0000-0000-0000-000000000002"));

    @Mock private LoadAccountPort loadAccountPort;
    @Mock private UpdateAccountBalancePort updateAccountBalancePort;
    @Mock private SaveTransferPort saveTransferPort;
    @Mock private IdempotencyPort idempotencyPort;
    @Mock private OutboxEventPort outboxEventPort;
    @Mock private PaymentGatewayPort paymentGatewayPort;

    private GatewayPaymentWorkflowService service;

    @BeforeEach
    void setUp() {
        service = new GatewayPaymentWorkflowService(
                loadAccountPort, updateAccountBalancePort, saveTransferPort,
                idempotencyPort, outboxEventPort, paymentGatewayPort, new AccountAccessPolicy());
        lenient().when(paymentGatewayPort.verifySignature(anyString(), anyString())).thenReturn(true);
    }

    private Transfer transferWithStatus(TransferStatus status) {
        return new Transfer(
                TransferId.generate(), IdempotencyKey.of("PSP-KEY-1"), CUSTOMER_ID, MERCHANT_ID,
                Money.of(200.00, Currency.TRY), status, PaymentGatewayType.STRIPE, EXTERNAL_REF, null, Instant.now());
    }

    private WebhookCommand webhook() {
        return new WebhookCommand("{}", "sig", "payment_intent.succeeded", EXTERNAL_REF, new BigDecimal("200.00"), "TRY");
    }

    @Test
    @DisplayName("Webhook loads the transfer under a row lock, then captures and debits once")
    void shouldCaptureAuthorizedTransferUsingRowLock() {
        Account customer = new Account(CUSTOMER_ID, "CUST", Money.of(1000.00, Currency.TRY), AccountStatus.ACTIVE, 0L, "CUST-1");
        Account merchant = new Account(MERCHANT_ID, "MERCH", Money.of(0.00, Currency.TRY), AccountStatus.ACTIVE, 0L, null);
        when(saveTransferPort.findByExternalReferenceIdForUpdate(EXTERNAL_REF))
                .thenReturn(Optional.of(transferWithStatus(TransferStatus.AUTHORIZED)));
        when(loadAccountPort.loadAccountWithLock(CUSTOMER_ID)).thenReturn(Optional.of(customer));
        when(loadAccountPort.loadAccountWithLock(MERCHANT_ID)).thenReturn(Optional.of(merchant));

        WebhookResult result = service.processWebhook(webhook());

        assertThat(result.status()).isEqualTo(TransferStatus.SETTLED);
        assertThat(customer.getBalance().amount()).isEqualByComparingTo("800.00");
        assertThat(merchant.getBalance().amount()).isEqualByComparingTo("200.00");
        verify(saveTransferPort, never()).findByExternalReferenceId(anyString());
    }

    @Test
    @DisplayName("A duplicate webhook for an already SETTLED transfer is a replay and moves no money")
    void shouldNotDebitAgainForAlreadySettledTransfer() {
        when(saveTransferPort.findByExternalReferenceIdForUpdate(EXTERNAL_REF))
                .thenReturn(Optional.of(transferWithStatus(TransferStatus.SETTLED)));

        WebhookResult result = service.processWebhook(webhook());

        assertThat(result.processed()).isTrue();
        assertThat(result.message()).contains("Idempotent Webhook Replay");
        verifyNoInteractions(loadAccountPort, updateAccountBalancePort, outboxEventPort);
        verify(saveTransferPort, never()).saveTransfer(any());
        verify(saveTransferPort, never()).recordDoubleEntryLedger(any(), any(), any(), any());
    }

    private GatewayPaymentCommand paymentBy(Requester requester) {
        return new GatewayPaymentCommand(IdempotencyKey.of("PSP-KEY-2"), CUSTOMER_ID, MERCHANT_ID,
                Money.of(200.00, Currency.TRY), PaymentGatewayType.STRIPE, "a@b.c", "order", requester);
    }

    private void stubAccounts() {
        Account customer = new Account(CUSTOMER_ID, "CUST", Money.of(1000.00, Currency.TRY), AccountStatus.ACTIVE, 0L, "CUST-1");
        Account merchant = new Account(MERCHANT_ID, "MERCH", Money.of(0.00, Currency.TRY), AccountStatus.ACTIVE, 0L, "MERCH-1");
        when(loadAccountPort.loadAccount(CUSTOMER_ID)).thenReturn(Optional.of(customer));
        when(loadAccountPort.loadAccount(MERCHANT_ID)).thenReturn(Optional.of(merchant));
    }

    @Test
    @DisplayName("Payment from an account the requester does not own is rejected before reaching the gateway")
    void shouldRejectPaymentFromAccountNotOwned() {
        stubAccounts();
        when(idempotencyPort.acquireLock(any())).thenReturn(true);

        assertThatThrownBy(() -> service.initiatePayment(paymentBy(Requester.customer("SOMEONE-ELSE"))))
                .isInstanceOf(AccountNotFoundException.class);

        verify(paymentGatewayPort, never()).authorizePayment(any());
        verify(saveTransferPort, never()).saveTransfer(any());
        verify(idempotencyPort).releaseLock(IdempotencyKey.of("SOMEONE-ELSE:PSP-KEY-2"));
    }

    @Test
    @DisplayName("Payment from the requester's own account is authorized")
    void shouldAuthorizePaymentFromOwnAccount() {
        stubAccounts();
        when(idempotencyPort.acquireLock(any())).thenReturn(true);
        when(paymentGatewayPort.authorizePayment(any())).thenReturn(
                new PaymentGatewayPort.GatewayAuthorizationResponse(true, EXTERNAL_REF, "secret", "ok"));

        GatewayPaymentResponse response = service.initiatePayment(paymentBy(Requester.customer("CUST-1")));

        assertThat(response.status()).isEqualTo(TransferStatus.AUTHORIZED);
        verify(idempotencyPort).acquireLock(IdempotencyKey.of("CUST-1:PSP-KEY-2"));
    }
}
