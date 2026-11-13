package com.bank.payment.infrastructure.persistence.repository;

import com.bank.payment.infrastructure.persistence.entity.AccountJpaEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface SpringDataAccountRepository extends JpaRepository<AccountJpaEntity, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT a FROM AccountJpaEntity a WHERE a.id = :id")
    Optional<AccountJpaEntity> findByIdWithPessimisticLock(@Param("id") UUID id);

    /** Scalar projection (not a managed entity): never populates the persistence context. */
    @Query("SELECT a.ownerCustomerId AS ownerCustomerId, a.status AS status FROM AccountJpaEntity a WHERE a.id = :id")
    Optional<OwnershipProjection> findOwnershipById(@Param("id") UUID id);

    interface OwnershipProjection {
        String getOwnerCustomerId();
        com.bank.payment.domain.model.account.AccountStatus getStatus();
    }

    Optional<AccountJpaEntity> findByCustomerNumber(String customerNumber);
}

