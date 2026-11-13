package com.bank.payment.infrastructure.persistence.repository;

import com.bank.payment.infrastructure.persistence.entity.TransferJpaEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface SpringDataTransferRepository extends JpaRepository<TransferJpaEntity, UUID> {
    Optional<TransferJpaEntity> findByIdempotencyKey(String idempotencyKey);
    Optional<TransferJpaEntity> findByExternalReferenceId(String externalReferenceId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT t FROM TransferJpaEntity t WHERE t.externalReferenceId = :externalReferenceId")
    Optional<TransferJpaEntity> findByExternalReferenceIdForUpdate(@Param("externalReferenceId") String externalReferenceId);
    List<TransferJpaEntity> findTop50ByOrderByCreatedAtDesc();

    List<TransferJpaEntity> findAllByOrderByCreatedAtDesc(Pageable pageable);

    @Query("SELECT t FROM TransferJpaEntity t WHERE t.sourceAccountId IN :ids OR t.targetAccountId IN :ids ORDER BY t.createdAt DESC")
    List<TransferJpaEntity> findInvolvingAccounts(@Param("ids") Collection<UUID> ids, Pageable pageable);
}
