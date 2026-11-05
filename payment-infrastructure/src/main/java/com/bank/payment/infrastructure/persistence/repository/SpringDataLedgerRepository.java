package com.bank.payment.infrastructure.persistence.repository;

import com.bank.payment.infrastructure.persistence.entity.LedgerJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface SpringDataLedgerRepository extends JpaRepository<LedgerJpaEntity, UUID> {
    List<LedgerJpaEntity> findByAccountIdOrderByCreatedAtDesc(UUID accountId);
    List<LedgerJpaEntity> findTop50ByOrderByCreatedAtDesc();
}
