package com.bank.payment.infrastructure.persistence.entity;

import com.bank.payment.domain.model.account.AccountId;
import com.bank.payment.domain.model.shared.Currency;
import com.bank.payment.domain.model.shared.Money;
import com.bank.payment.domain.model.transfer.IdempotencyKey;
import com.bank.payment.domain.model.transfer.Transfer;
import com.bank.payment.domain.model.transfer.TransferId;
import com.bank.payment.domain.model.transfer.TransferStatus;
import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(
    name = "transfers",
    indexes = {
        @Index(name = "idx_tx_idempotency", columnList = "idempotency_key", unique = true)
    }
)
public class TransferJpaEntity {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "idempotency_key", nullable = false, unique = true, length = 120)
    private String idempotencyKey;

    @Column(name = "source_account_id", nullable = false)
    private UUID sourceAccountId;

    @Column(name = "target_account_id", nullable = false)
    private UUID targetAccountId;

    @Column(name = "amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(name = "currency", nullable = false, length = 3)
    private Currency currency;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private TransferStatus status;

    @Column(name = "rejection_reason", length = 255)
    private String rejectionReason;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public TransferJpaEntity() {}

    public TransferJpaEntity(UUID id, String idempotencyKey, UUID sourceAccountId, UUID targetAccountId,
                             BigDecimal amount, Currency currency, TransferStatus status, String rejectionReason, Instant createdAt) {
        this.id = id;
        this.idempotencyKey = idempotencyKey;
        this.sourceAccountId = sourceAccountId;
        this.targetAccountId = targetAccountId;
        this.amount = amount;
        this.currency = currency;
        this.status = status;
        this.rejectionReason = rejectionReason;
        this.createdAt = createdAt;
    }

    public Transfer toDomain() {
        return new Transfer(
                TransferId.of(this.id),
                IdempotencyKey.of(this.idempotencyKey),
                AccountId.of(this.sourceAccountId),
                AccountId.of(this.targetAccountId),
                Money.of(this.amount, this.currency),
                this.status,
                this.rejectionReason,
                this.createdAt != null ? this.createdAt : Instant.now()
        );
    }

    public static TransferJpaEntity fromDomain(Transfer transfer) {
        return new TransferJpaEntity(
                transfer.getId().value(),
                transfer.getIdempotencyKey().value(),
                transfer.getSourceAccountId().value(),
                transfer.getTargetAccountId().value(),
                transfer.getAmount().amount(),
                transfer.getAmount().currency(),
                transfer.getStatus(),
                transfer.getRejectionReason(),
                transfer.getCreatedAt()
        );
    }

    public UUID getId() { return id; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public UUID getSourceAccountId() { return sourceAccountId; }
    public UUID getTargetAccountId() { return targetAccountId; }
    public BigDecimal getAmount() { return amount; }
    public Currency getCurrency() { return currency; }
    public TransferStatus getStatus() { return status; }
    public String getRejectionReason() { return rejectionReason; }
    public Instant getCreatedAt() { return createdAt; }
}

