package com.bank.payment.infrastructure.persistence.entity;

import com.bank.payment.domain.model.account.Account;
import com.bank.payment.domain.model.account.AccountId;
import com.bank.payment.domain.model.account.AccountStatus;
import com.bank.payment.domain.model.shared.Currency;
import com.bank.payment.domain.model.shared.Money;
import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "accounts")
public class AccountJpaEntity {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "customer_number", nullable = false, unique = true, length = 100)
    private String customerNumber;

    @Column(name = "balance", nullable = false, precision = 19, scale = 2)
    private BigDecimal balance;

    @Enumerated(EnumType.STRING)
    @Column(name = "currency", nullable = false, length = 3)
    private Currency currency;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private AccountStatus status;

    @Column(name = "owner_customer_id", length = 64)
    private String ownerCustomerId;

    @Version
    @Column(name = "version")
    private Long version;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private Instant updatedAt;

    public AccountJpaEntity() {}

    public AccountJpaEntity(UUID id, String customerNumber, BigDecimal balance, Currency currency, AccountStatus status, Long version, String ownerCustomerId) {
        this.id = id;
        this.customerNumber = customerNumber;
        this.balance = balance;
        this.currency = currency;
        this.status = status;
        this.version = version;
        this.ownerCustomerId = ownerCustomerId;
    }

    public Account toDomain() {
        return new Account(
                AccountId.of(this.id),
                this.customerNumber,
                Money.of(this.balance, this.currency),
                this.status,
                this.version,
                this.ownerCustomerId
        );
    }

    public static AccountJpaEntity fromDomain(Account account) {
        return new AccountJpaEntity(
                account.getId().value(),
                account.getCustomerNumber(),
                account.getBalance().amount(),
                account.getBalance().currency(),
                account.getStatus(),
                account.getVersion(),
                account.getOwnerCustomerId()
        );
    }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public String getCustomerNumber() { return customerNumber; }
    public void setCustomerNumber(String customerNumber) { this.customerNumber = customerNumber; }
    public BigDecimal getBalance() { return balance; }
    public void setBalance(BigDecimal balance) { this.balance = balance; }
    public Currency getCurrency() { return currency; }
    public void setCurrency(Currency currency) { this.currency = currency; }
    public AccountStatus getStatus() { return status; }
    public void setStatus(AccountStatus status) { this.status = status; }
    public Long getVersion() { return version; }
    public void setVersion(Long version) { this.version = version; }
    public String getOwnerCustomerId() { return ownerCustomerId; }
    public void setOwnerCustomerId(String ownerCustomerId) { this.ownerCustomerId = ownerCustomerId; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
