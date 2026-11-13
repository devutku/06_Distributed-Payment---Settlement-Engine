package com.bank.payment.infrastructure.persistence.adapter;

import com.bank.payment.application.port.out.SaveTransferPort;
import com.bank.payment.domain.model.account.Account;
import com.bank.payment.domain.model.transfer.IdempotencyKey;
import com.bank.payment.domain.model.transfer.Transfer;
import com.bank.payment.infrastructure.persistence.entity.LedgerJpaEntity;
import com.bank.payment.infrastructure.persistence.entity.TransferJpaEntity;
import com.bank.payment.infrastructure.persistence.repository.SpringDataLedgerRepository;
import com.bank.payment.infrastructure.persistence.repository.SpringDataTransferRepository;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Component
public class TransferPersistenceAdapter implements SaveTransferPort {

    private final SpringDataTransferRepository transferRepository;
    private final SpringDataLedgerRepository ledgerRepository;

    public TransferPersistenceAdapter(
            SpringDataTransferRepository transferRepository,
            SpringDataLedgerRepository ledgerRepository) {
        this.transferRepository = transferRepository;
        this.ledgerRepository = ledgerRepository;
    }

    @Override
    public void saveTransfer(Transfer transfer) {
        TransferJpaEntity entity = TransferJpaEntity.fromDomain(transfer);
        transferRepository.save(entity);
    }

    @Override
    public Optional<Transfer> findByIdempotencyKey(IdempotencyKey key) {
        return transferRepository.findByIdempotencyKey(key.value())
                .map(TransferJpaEntity::toDomain);
    }

    @Override
    public Optional<Transfer> findByExternalReferenceId(String externalReferenceId) {
        return transferRepository.findByExternalReferenceId(externalReferenceId)
                .map(TransferJpaEntity::toDomain);
    }

    @Override
    public Optional<Transfer> findByExternalReferenceIdForUpdate(String externalReferenceId) {
        return transferRepository.findByExternalReferenceIdForUpdate(externalReferenceId)
                .map(TransferJpaEntity::toDomain);
    }

    @Override
    public void recordDoubleEntryLedger(Transfer transfer, Account sourceAccount, Account targetAccount, String description) {
        // Entry 1: DEBIT source account
        LedgerJpaEntity debitEntry = new LedgerJpaEntity(
                UUID.randomUUID(),
                transfer.getId().value(),
                sourceAccount.getId().value(),
                "DEBIT",
                transfer.getAmount().amount(),
                sourceAccount.getBalance().amount(),
                description != null ? description : "Debit transfer to " + targetAccount.getCustomerNumber()
        );

        // Entry 2: CREDIT target account
        LedgerJpaEntity creditEntry = new LedgerJpaEntity(
                UUID.randomUUID(),
                transfer.getId().value(),
                targetAccount.getId().value(),
                "CREDIT",
                transfer.getAmount().amount(),
                targetAccount.getBalance().amount(),
                description != null ? description : "Credit transfer from " + sourceAccount.getCustomerNumber()
        );

        ledgerRepository.saveAll(List.of(debitEntry, creditEntry));
    }
}
