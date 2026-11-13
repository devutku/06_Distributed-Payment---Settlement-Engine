package com.bank.payment.domain.model.account;

import com.bank.payment.domain.exception.AccountInactiveException;
import com.bank.payment.domain.exception.InsufficientFundsException;
import com.bank.payment.domain.model.shared.Money;
import java.util.Objects;

public class Account {

    private final AccountId id;
    private final String customerNumber;
    private Money balance;
    private AccountStatus status;
    private final Long version; // Optimistic locking için sürüm bilgisi
    private final String ownerCustomerId; // null: bankaya ait iç hesap

    public Account(AccountId id, String customerNumber, Money balance, AccountStatus status, Long version, String ownerCustomerId) {
        this.id = Objects.requireNonNull(id, "AccountId null olamaz");
        this.customerNumber = Objects.requireNonNull(customerNumber, "CustomerNumber null olamaz");
        this.balance = Objects.requireNonNull(balance, "Balance null olamaz");
        this.status = Objects.requireNonNull(status, "Status null olamaz");
        this.version = version;
        this.ownerCustomerId = ownerCustomerId;
    }

    public void debit(Money amount) {
        validateActive();
        if (this.balance.isLessThan(amount)) {
            throw new InsufficientFundsException("Hesapta yeterli bakiye bulunmuyor. Mevcut: " + balance.amount());
        }
        this.balance = this.balance.subtract(amount);
    }

    public void credit(Money amount) {
        validateActive();
        this.balance = this.balance.add(amount);
    }

    private void validateActive() {
        if (this.status != AccountStatus.ACTIVE) {
            throw new AccountInactiveException("İşlem yapılamaz. Hesap durumu: " + this.status);
        }
    }

    public AccountId getId() { return id; }
    public String getCustomerNumber() { return customerNumber; }
    public Money getBalance() { return balance; }
    public AccountStatus getStatus() { return status; }
    public Long getVersion() { return version; }
    public String getOwnerCustomerId() { return ownerCustomerId; }

    public boolean isOwnedBy(String customerId) {
        return customerId != null && customerId.equals(ownerCustomerId);
    }
}

