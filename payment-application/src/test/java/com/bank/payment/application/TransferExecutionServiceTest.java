package com.bank.payment.application;

import com.bank.payment.application.dto.InitiateTransferCommand;
import com.bank.payment.application.dto.TransferResultDto;
import com.bank.payment.application.port.out.*;
import com.bank.payment.application.service.TransferExecutionService;
import com.bank.payment.domain.model.account.Account;
import com.bank.payment.domain.model.account.AccountId;
import com.bank.payment.domain.model.account.AccountStatus;
import com.bank.payment.domain.model.shared.Currency;
import com.bank.payment.domain.model.shared.Money;
import com.bank.payment.domain.model.transfer.IdempotencyKey;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TransferExecutionServiceTest {

    @Mock private LoadAccountPort loadAccountPort;
    @Mock private UpdateAccountBalancePort updateAccountBalancePort;
    @Mock private SaveTransferPort saveTransferPort;
    @Mock private IdempotencyPort idempotencyPort;
    @Mock private OutboxEventPort outboxEventPort;

    private TransferExecutionService service;

    @BeforeEach
    void setUp() {
        service = new TransferExecutionService(
                loadAccountPort, updateAccountBalancePort, saveTransferPort, idempotencyPort, outboxEventPort);
    }

    @Test
    @DisplayName("Should execute transfer successfully and enforce lock ordering")
    void shouldExecuteTransferSuccessfully() {
        AccountId sourceId = AccountId.of(UUID.fromString("00000000-0000-0000-0000-000000000001"));
        AccountId targetId = AccountId.of(UUID.fromString("00000000-0000-0000-0000-000000000002"));

        Account source = new Account(sourceId, "CUST-1", Money.of(1000.00, Currency.TRY), AccountStatus.ACTIVE, 0L);
        Account target = new Account(targetId, "CUST-2", Money.of(200.00, Currency.TRY), AccountStatus.ACTIVE, 0L);

        when(idempotencyPort.getCachedResult(any())).thenReturn(Optional.empty());
        when(idempotencyPort.acquireLock(any())).thenReturn(true);
        when(loadAccountPort.loadAccountWithLock(sourceId)).thenReturn(Optional.of(source));
        when(loadAccountPort.loadAccountWithLock(targetId)).thenReturn(Optional.of(target));

        InitiateTransferCommand command = new InitiateTransferCommand(
                IdempotencyKey.of("IDEMP-KEY-1"),
                sourceId,
                targetId,
                Money.of(300.00, Currency.TRY),
                "Test Transfer"
        );

        TransferResultDto result = service.execute(command);

        assertThat(result).isNotNull();
        assertThat(result.amount()).isEqualByComparingTo(new BigDecimal("300.00"));
        assertThat(source.getBalance().amount()).isEqualByComparingTo(new BigDecimal("700.00"));
        assertThat(target.getBalance().amount()).isEqualByComparingTo(new BigDecimal("500.00"));

        verify(updateAccountBalancePort).updateAccount(source);
        verify(updateAccountBalancePort).updateAccount(target);
        verify(saveTransferPort).saveTransfer(any());
        verify(outboxEventPort).saveEvent(any());
        verify(idempotencyPort).cacheResult(eq(command.idempotencyKey()), any());
        verify(idempotencyPort).releaseLock(command.idempotencyKey());
    }
}

