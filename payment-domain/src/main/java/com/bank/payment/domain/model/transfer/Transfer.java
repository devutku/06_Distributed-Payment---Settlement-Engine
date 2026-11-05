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
    private PaymentGatewayType gatewayType;
    private String externalReferenceId;
    private String rejectionReason;
    private final Instant createdAt;

    public Transfer(TransferId id, IdempotencyKey idempotencyKey, AccountId sourceAccountId, 
                    AccountId targetAccountId, Money amount) {
        this(id, idempotencyKey, sourceAccountId, targetAccountId, amount, PaymentGatewayType.INTERNAL);
    }

    public Transfer(TransferId id, IdempotencyKey idempotencyKey, AccountId sourceAccountId, 
                    AccountId targetAccountId, Money amount, PaymentGatewayType gatewayType) {
        this.id = Objects.requireNonNull(id, "TransferId null olamaz");
        this.idempotencyKey = Objects.requireNonNull(idempotencyKey, "IdempotencyKey null olamaz");
        this.sourceAccountId = Objects.requireNonNull(sourceAccountId, "SourceAccountId null olamaz");
        this.targetAccountId = Objects.requireNonNull(targetAccountId, "TargetAccountId null olamaz");
        this.amount = Objects.requireNonNull(amount, "Amount null olamaz");
        this.status = TransferStatus.PENDING;
        this.gatewayType = gatewayType != null ? gatewayType : PaymentGatewayType.INTERNAL;
        this.createdAt = Instant.now();
    }

    public Transfer(TransferId id, IdempotencyKey idempotencyKey, AccountId sourceAccountId, 
                    AccountId targetAccountId, Money amount, TransferStatus status, 
                    PaymentGatewayType gatewayType, String externalReferenceId,
                    String rejectionReason, Instant createdAt) {
        this.id = Objects.requireNonNull(id);
        this.idempotencyKey = Objects.requireNonNull(idempotencyKey);
        this.sourceAccountId = Objects.requireNonNull(sourceAccountId);
        this.targetAccountId = Objects.requireNonNull(targetAccountId);
        this.amount = Objects.requireNonNull(amount);
        this.status = Objects.requireNonNull(status);
        this.gatewayType = gatewayType != null ? gatewayType : PaymentGatewayType.INTERNAL;
        this.externalReferenceId = externalReferenceId;
        this.rejectionReason = rejectionReason;
        this.createdAt = Objects.requireNonNull(createdAt);
    }

    public void authorize(String externalReferenceId) {
        if (this.status != TransferStatus.PENDING) {
            throw new IllegalStateException("Sadece PENDING durumundaki işlemler AUTHORIZED yapılabilir. Mevcut durum: " + this.status);
        }
        this.externalReferenceId = Objects.requireNonNull(externalReferenceId, "externalReferenceId null olamaz");
        this.status = TransferStatus.AUTHORIZED;
    }

    public void capture() {
        if (this.status != TransferStatus.AUTHORIZED && this.status != TransferStatus.PENDING) {
            throw new IllegalStateException("İşlemi CAPTURE etmek için AUTHORIZED veya PENDING olmalıdır. Mevcut durum: " + this.status);
        }
        this.status = TransferStatus.CAPTURED;
    }

    public void markAsSettled() {
        if (this.status == TransferStatus.REJECTED) {
            throw new IllegalStateException("REJECTED transferler SETTLED yapılamaz");
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
    public PaymentGatewayType getGatewayType() { return gatewayType; }
    public String getExternalReferenceId() { return externalReferenceId; }
    public String getRejectionReason() { return rejectionReason; }
    public Instant getCreatedAt() { return createdAt; }
}
