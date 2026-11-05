package com.bank.payment.infrastructure.persistence.repository;

import com.bank.payment.infrastructure.persistence.entity.TransferJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface SpringDataTransferRepository extends JpaRepository<TransferJpaEntity, UUID> {
    Optional<TransferJpaEntity> findByIdempotencyKey(String idempotencyKey);
    Optional<TransferJpaEntity> findByExternalReferenceId(String externalReferenceId);
    List<TransferJpaEntity> findTop50ByOrderByCreatedAtDesc();
}
