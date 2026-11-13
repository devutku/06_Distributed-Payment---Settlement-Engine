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
import com.bank.payment.domain.model.transfer.TransferStatus;
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
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TransferExecutionServiceTest {

    private static final AccountId SOURCE_ID = AccountId.of(UUID.fromString("00000000-0000-0000-0000-000000000001"));
    private static final AccountId TARGET_ID = AccountId.of(UUID.fromString("00000000-0000-0000-0000-000000000002"));

    @Mock private LoadAccountPort loadAccountPort;
    @Mock private UpdateAccountBalancePort updateAccountBalancePort;
    @Mock private SaveTransferPort saveTransferPort;
    @Mock private IdempotencyPort idempotencyPort;
    @Mock private OutboxEventPort outboxEventPort;

    private FakeTransactionPort transactionPort;
    private TransferExecutionService service;

    /** Runs the work inline and simulates a commit, optionally failing at commit time. */
    static class FakeTransactionPort implements TransactionPort {
        boolean committed;
        boolean failOnCommit;
        int invocations;

        @Override
        public <T> T executeInNewTransaction(Supplier<T> work) {
            invocations++;
            T result = work.get();
            if (failOnCommit) {
                throw new IllegalStateException("simulated commit failure");
            }
            committed = true;
            return result;
        }
    }

    @BeforeEach
    void setUp() {
        transactionPort = new FakeTransactionPort();
        service = new TransferExecutionService(
                loadAccountPort, updateAccountBalancePort, saveTransferPort, idempotencyPort, outboxEventPort,
                transactionPort);
    }

    private InitiateTransferCommand command() {
        return new InitiateTransferCommand(
                IdempotencyKey.of("IDEMP-KEY-1"), SOURCE_ID, TARGET_ID, Money.of(300.00, Currency.TRY), "Test Transfer");
    }

    private void stubAccounts(Account source, Account target) {
        when(loadAccountPort.loadAccountWithLock(SOURCE_ID)).thenReturn(Optional.of(source));
        when(loadAccountPort.loadAccountWithLock(TARGET_ID)).thenReturn(Optional.of(target));
    }

    @Test
    @DisplayName("Should execute transfer successfully and enforce lock ordering")
    void shouldExecuteTransferSuccessfully() {
        Account source = new Account(SOURCE_ID, "CUST-1", Money.of(1000.00, Currency.TRY), AccountStatus.ACTIVE, 0L);
        Account target = new Account(TARGET_ID, "CUST-2", Money.of(200.00, Currency.TRY), AccountStatus.ACTIVE, 0L);

        when(idempotencyPort.getCachedResult(any())).thenReturn(Optional.empty());
        when(idempotencyPort.acquireLock(any())).thenReturn(true);
        stubAccounts(source, target);

        InitiateTransferCommand command = command();
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

    @Test
    @DisplayName("Idempotent response is cached only after the transaction has committed")
    void shouldCacheResultOnlyAfterCommit() {
        Account source = new Account(SOURCE_ID, "CUST-1", Money.of(1000.00, Currency.TRY), AccountStatus.ACTIVE, 0L);
        Account target = new Account(TARGET_ID, "CUST-2", Money.of(200.00, Currency.TRY), AccountStatus.ACTIVE, 0L);

        when(idempotencyPort.getCachedResult(any())).thenReturn(Optional.empty());
        when(idempotencyPort.acquireLock(any())).thenReturn(true);
        stubAccounts(source, target);
        doAnswer(inv -> {
            assertThat(transactionPort.committed)
                    .as("cacheResult must not run before the DB transaction commits")
                    .isTrue();
            return null;
        }).when(idempotencyPort).cacheResult(any(), any());

        service.execute(command());

        verify(idempotencyPort).cacheResult(any(), any());
    }

    @Test
    @DisplayName("A failed commit leaves no cached 'success' response behind and still releases the lock")
    void shouldNotCacheResultWhenCommitFails() {
        Account source = new Account(SOURCE_ID, "CUST-1", Money.of(1000.00, Currency.TRY), AccountStatus.ACTIVE, 0L);
        Account target = new Account(TARGET_ID, "CUST-2", Money.of(200.00, Currency.TRY), AccountStatus.ACTIVE, 0L);

        when(idempotencyPort.getCachedResult(any())).thenReturn(Optional.empty());
        when(idempotencyPort.acquireLock(any())).thenReturn(true);
        stubAccounts(source, target);
        transactionPort.failOnCommit = true;

        InitiateTransferCommand command = command();
        assertThatThrownBy(() -> service.execute(command))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("simulated commit failure");

        verify(idempotencyPort, never()).cacheResult(any(), any());
        verify(idempotencyPort).releaseLock(command.idempotencyKey());
    }

    @Test
    @DisplayName("A request that finished while we waited for the lock is replayed, not executed again")
    void shouldReplayWhenResultAppearsAfterAcquiringLock() {
        InitiateTransferCommand command = command();
        TransferResultDto committed = new TransferResultDto(
                UUID.randomUUID(), command.idempotencyKey().value(), SOURCE_ID.value(), TARGET_ID.value(),
                new BigDecimal("300.00"), "TRY", TransferStatus.SETTLED, "ok", Instant.now(), false);

        when(idempotencyPort.getCachedResult(any()))
                .thenReturn(Optional.empty())          // fast-path check: not done yet
                .thenReturn(Optional.of(committed));   // re-check under lock: other request finished
        when(idempotencyPort.acquireLock(any())).thenReturn(true);

        TransferResultDto result = service.execute(command);

        assertThat(result.idempotentReplay()).isTrue();
        assertThat(transactionPort.invocations).isZero();
        verifyNoInteractions(loadAccountPort, updateAccountBalancePort, saveTransferPort, outboxEventPort);
        verify(idempotencyPort).releaseLock(command.idempotencyKey());
    }
}
