package com.bank.payment.domain.model.transfer;

import com.bank.payment.domain.model.account.AccountId;
import com.bank.payment.domain.model.shared.Money;
import java.time.Instant;
import java.util.Objects;

public class Transfer {

    private final TransferId id;
    private final IdempotencyKey idempotencyKey;
    private final AccountId sourceAccountId;
    private final AccountId targetAccountId;
    private final Money amount;
    private TransferStatus status;
    private String rejectionReason;
    private final Instant createdAt;

    public Transfer(TransferId id, IdempotencyKey idempotencyKey, AccountId sourceAccountId, 
                    AccountId targetAccountId, Money amount) {
        this.id = Objects.requireNonNull(id, "TransferId null olamaz");
        this.idempotencyKey = Objects.requireNonNull(idempotencyKey, "IdempotencyKey null olamaz");
        this.sourceAccountId = Objects.requireNonNull(sourceAccountId, "SourceAccountId null olamaz");
        this.targetAccountId = Objects.requireNonNull(targetAccountId, "TargetAccountId null olamaz");
        this.amount = Objects.requireNonNull(amount, "Amount null olamaz");
        this.status = TransferStatus.PENDING;
        this.createdAt = Instant.now();
    }

    public Transfer(TransferId id, IdempotencyKey idempotencyKey, AccountId sourceAccountId, 
                    AccountId targetAccountId, Money amount, TransferStatus status, String rejectionReason, Instant createdAt) {
        this.id = Objects.requireNonNull(id);
        this.idempotencyKey = Objects.requireNonNull(idempotencyKey);
        this.sourceAccountId = Objects.requireNonNull(sourceAccountId);
        this.targetAccountId = Objects.requireNonNull(targetAccountId);
        this.amount = Objects.requireNonNull(amount);
        this.status = Objects.requireNonNull(status);
        this.rejectionReason = rejectionReason;
        this.createdAt = Objects.requireNonNull(createdAt);
    }

    public void markAsSettled() {
        if (this.status != TransferStatus.PENDING) {
            throw new IllegalStateException("Sadece PENDING transferler SETTLED yapılabilir");
        }
        this.status = TransferStatus.SETTLED;
    }

    public void markAsRejected(String reason) {
        this.status = TransferStatus.REJECTED;
        this.rejectionReason = reason;
    }

    public TransferId getId() { return id; }
    public IdempotencyKey getIdempotencyKey() { return idempotencyKey; }
    public AccountId getSourceAccountId() { return sourceAccountId; }
    public AccountId getTargetAccountId() { return targetAccountId; }
    public Money getAmount() { return amount; }
    public TransferStatus getStatus() { return status; }
    public String getRejectionReason() { return rejectionReason; }
    public Instant getCreatedAt() { return createdAt; }
}
