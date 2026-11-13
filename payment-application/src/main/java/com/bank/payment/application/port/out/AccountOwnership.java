package com.bank.payment.application.port.out;

import com.bank.payment.domain.model.account.AccountId;
import com.bank.payment.domain.model.account.AccountStatus;

/** Read-only scalar view of who owns an account; carries no balance/version, so it cannot go stale in a way that matters. */
public record AccountOwnership(AccountId accountId, String ownerCustomerId, AccountStatus status) {}
