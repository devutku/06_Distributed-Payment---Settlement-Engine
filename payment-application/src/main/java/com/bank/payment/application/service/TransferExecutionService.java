package com.bank.payment.application.service;

import com.bank.payment.application.dto.AccountBalanceDto;
import com.bank.payment.application.dto.AccountStatementDto;
import com.bank.payment.application.dto.InitiateTransferCommand;
import com.bank.payment.application.dto.TransferResultDto;
import com.bank.payment.application.port.in.GetAccountBalanceQuery;
import com.bank.payment.application.port.in.GetAccountStatementQuery;
import com.bank.payment.application.port.in.InitiateTransferUseCase;
import com.bank.payment.application.port.out.*;
import com.bank.payment.domain.event.TransferSettledEvent;
import com.bank.payment.application.security.AccountAccessPolicy;
import com.bank.payment.domain.exception.AccountNotFoundException;
import com.bank.payment.domain.model.account.Account;
import com.bank.payment.domain.model.account.AccountId;
import com.bank.payment.domain.model.transfer.IdempotencyKey;
import com.bank.payment.domain.model.transfer.Transfer;
import com.bank.payment.domain.model.transfer.TransferId;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public class TransferExecutionService implements InitiateTransferUseCase, GetAccountBalanceQuery, GetAccountStatementQuery {

    private final LoadAccountPort loadAccountPort;
    private final UpdateAccountBalancePort updateAccountBalancePort;
    private final SaveTransferPort saveTransferPort;
    private final IdempotencyPort idempotencyPort;
    private final OutboxEventPort outboxEventPort;
    private final TransactionPort transactionPort;
    private final AccountAccessPolicy accessPolicy;

    public TransferExecutionService(
            LoadAccountPort loadAccountPort,
            UpdateAccountBalancePort updateAccountBalancePort,
            SaveTransferPort saveTransferPort,
            IdempotencyPort idempotencyPort,
            OutboxEventPort outboxEventPort,
            TransactionPort transactionPort,
            AccountAccessPolicy accessPolicy) {
        this.loadAccountPort = Objects.requireNonNull(loadAccountPort);
        this.updateAccountBalancePort = Objects.requireNonNull(updateAccountBalancePort);
        this.saveTransferPort = Objects.requireNonNull(saveTransferPort);
        this.idempotencyPort = Objects.requireNonNull(idempotencyPort);
        this.outboxEventPort = Objects.requireNonNull(outboxEventPort);
        this.transactionPort = Objects.requireNonNull(transactionPort);
        this.accessPolicy = Objects.requireNonNull(accessPolicy);
    }

    /**
     * Idempotent transfer execution.
     *
     * Idempotency bookkeeping (Redis lock + cached response) deliberately lives
     * OUTSIDE the database transaction: the response is cached only after the
     * transaction has committed. Caching inside the transaction would leave a
     * "success" response in Redis for a transfer that was rolled back at commit.
     */
    @Override
    public TransferResultDto execute(InitiateTransferCommand command) {
        // Keys are scoped per requester so two users can never collide on (or replay) each other's key
        IdempotencyKey scopedKey = command.requester().scope(command.idempotencyKey());

        // 1. Idempotency Fast-Path: Check if already completed
        Optional<TransferResultDto> cached = idempotencyPort.getCachedResult(scopedKey);
        if (cached.isPresent()) {
            return asReplay(cached.get());
        }

        // 2. In-flight Lock: Prevent concurrent identical requests
        boolean acquired = idempotencyPort.acquireLock(scopedKey);
        if (!acquired) {
            throw new IllegalStateException("Aynı Idempotency-Key ile devam eden bir işlem mevcut: " + scopedKey);
        }

        try {
            // Re-check under the lock: an identical request may have committed and
            // released the lock between our first cache check and acquireLock.
            Optional<TransferResultDto> cachedUnderLock = idempotencyPort.getCachedResult(scopedKey);
            if (cachedUnderLock.isPresent()) {
                return asReplay(cachedUnderLock.get());
            }

            // 3-7. All balance, transfer, ledger and outbox writes in one transaction.
            // Returns only after COMMIT; any failure propagates and nothing is cached.
            TransferResultDto result = transactionPort.executeInNewTransaction(() -> settleTransfer(command, scopedKey));

            // 8. Cache the response only now that the transfer is durably committed
            idempotencyPort.cacheResult(scopedKey, result);
            return result;
        } finally {
            idempotencyPort.releaseLock(scopedKey);
        }
    }

    private TransferResultDto asReplay(TransferResultDto replay) {
        return new TransferResultDto(
                replay.transferId(),
                replay.idempotencyKey(),
                replay.sourceAccountId(),
                replay.targetAccountId(),
                replay.amount(),
                replay.currency(),
                replay.status(),
                replay.message(),
                replay.timestamp(),
                true // idempotentReplay flag
        );
    }

    private TransferResultDto settleTransfer(InitiateTransferCommand command, IdempotencyKey scopedKey) {
        // 3. Deterministic Locking Order: Always lock lower AccountId first
        AccountId sourceId = command.sourceAccountId();
        AccountId targetId = command.targetAccountId();

        AccountId firstId = sourceId.compareTo(targetId) < 0 ? sourceId : targetId;
        AccountId secondId = sourceId.compareTo(targetId) < 0 ? targetId : sourceId;

        Account firstLocked = loadAccountPort.loadAccountWithLock(firstId)
                .orElseThrow(() -> new AccountNotFoundException(firstId));
        Account secondLocked = loadAccountPort.loadAccountWithLock(secondId)
                .orElseThrow(() -> new AccountNotFoundException(secondId));

        Account sourceAccount = sourceId.equals(firstId) ? firstLocked : secondLocked;
        Account targetAccount = targetId.equals(firstId) ? firstLocked : secondLocked;

        // Ownership checks (both accounts locked); failures look like "not found"
        accessPolicy.assertCanMoveFundsFrom(command.requester(), sourceAccount);
        accessPolicy.assertCanReceive(command.requester(), targetAccount);

        // 4. Domain Business Logic: Debit and Credit
        sourceAccount.debit(command.amount());
        targetAccount.credit(command.amount());

        // 5. Update Balances (Persistence Port)
        updateAccountBalancePort.updateAccount(sourceAccount);
        updateAccountBalancePort.updateAccount(targetAccount);

        // 6. Record Transfer & Double-Entry Ledger
        Transfer transfer = new Transfer(
                TransferId.generate(),
                scopedKey,
                sourceAccount.getId(),
                targetAccount.getId(),
                command.amount()
        );
        transfer.markAsSettled();

        saveTransferPort.saveTransfer(transfer);
        saveTransferPort.recordDoubleEntryLedger(transfer, sourceAccount, targetAccount, command.description());

        // 7. Transactional Outbox Event (Dual-Write Prevention)
        TransferSettledEvent settledEvent = new TransferSettledEvent(
                transfer.getId(),
                transfer.getIdempotencyKey(),
                sourceAccount.getId(),
                targetAccount.getId(),
                command.amount()
        );
        outboxEventPort.saveEvent(settledEvent);

        // Result construction (cached by execute() only after commit)
        return new TransferResultDto(
                transfer.getId().value(),
                command.idempotencyKey().value(),
                sourceAccount.getId().value(),
                targetAccount.getId().value(),
                command.amount().amount(),
                command.amount().currency().name(),
                transfer.getStatus(),
                "Transfer başarıyla tamamlandı",
                Instant.now(),
                false
        );
    }

    @Override
    public AccountBalanceDto getAccountById(AccountId accountId) {
        Account account = loadAccountPort.loadAccount(accountId)
                .orElseThrow(() -> new IllegalArgumentException("Hesap bulunamadı: " + accountId));
        return toBalanceDto(account);
    }

    @Override
    public List<AccountBalanceDto> getAllAccounts() {
        return loadAccountPort.loadAllAccounts().stream()
                .map(this::toBalanceDto)
                .toList();
    }

    @Override
    public AccountStatementDto getStatement(AccountId accountId) {
        // Query handled through repository / port
        Account account = loadAccountPort.loadAccount(accountId)
                .orElseThrow(() -> new IllegalArgumentException("Hesap bulunamadı: " + accountId));

        return new AccountStatementDto(
                account.getId().value(),
                account.getCustomerNumber(),
                account.getBalance().amount(),
                account.getBalance().currency().name(),
                List.of()
        );
    }

    private AccountBalanceDto toBalanceDto(Account account) {
        return new AccountBalanceDto(
                account.getId().value(),
                account.getCustomerNumber(),
                account.getBalance().amount(),
                account.getBalance().currency().name(),
                account.getStatus(),
                account.getVersion()
        );
    }
}

