package com.bank.payment.application.security;

import com.bank.payment.domain.exception.AccountNotFoundException;
import com.bank.payment.domain.model.account.Account;
import com.bank.payment.domain.model.account.AccountStatus;

public final class AccountAccessPolicy {

    public void assertCanMoveFundsFrom(Requester r, Account a) {
        boolean allowed = r.isSystem() || (r.isCustomer() && a.isOwnedBy(r.customerId()));
        if (!allowed) {
            throw new AccountNotFoundException(a.getId());
        }
    }

    public void assertCanReceive(Requester r, Account a) {
        if (!canReceive(r, a)) {
            throw new AccountNotFoundException(a.getId());
        }
    }

    /** A customer may only send/pay to an account that has an owner and is ACTIVE. */
    public boolean canReceive(Requester r, Account a) {
        return r.isSystem()
                || (r.isCustomer() && a.getOwnerCustomerId() != null && a.getStatus() == AccountStatus.ACTIVE);
    }

    public void assertCanView(Requester r, Account a) {
        if (!canView(r, a)) {
            throw new AccountNotFoundException(a.getId());
        }
    }

    public boolean canView(Requester r, Account a) {
        return r.isAdmin() || r.isSystem() || (r.isCustomer() && a.isOwnedBy(r.customerId()));
    }
}
