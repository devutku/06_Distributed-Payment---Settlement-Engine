package com.bank.payment.application.port.out;

import com.bank.payment.domain.model.account.Account;
import com.bank.payment.domain.model.transfer.IdempotencyKey;
import com.bank.payment.domain.model.transfer.Transfer;

import java.util.Optional;

public interface SaveTransferPort {
    void saveTransfer(Transfer transfer);
    Optional<Transfer> findByIdempotencyKey(IdempotencyKey key);
    Optional<Transfer> findByExternalReferenceId(String externalReferenceId);

    /**
     * Loads the transfer and holds a pessimistic write lock on its row until the
     * surrounding transaction ends. Used to serialize concurrent / retried webhooks.
     */
    Optional<Transfer> findByExternalReferenceIdForUpdate(String externalReferenceId);
    void recordDoubleEntryLedger(Transfer transfer, Account sourceAccount, Account targetAccount, String description);
}
