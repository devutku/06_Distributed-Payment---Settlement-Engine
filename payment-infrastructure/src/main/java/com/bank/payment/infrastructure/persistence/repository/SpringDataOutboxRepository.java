package com.bank.payment.infrastructure.persistence.repository;

import com.bank.payment.infrastructure.persistence.entity.OutboxJpaEntity;
import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface SpringDataOutboxRepository extends JpaRepository<OutboxJpaEntity, UUID> {
    @Query("SELECT o FROM OutboxJpaEntity o WHERE o.status = :status ORDER BY o.createdAt ASC")
    List<OutboxJpaEntity> findByStatusOrderByCreatedAtAsc(@Param("status") String status, Pageable pageable);

    /**
     * Claims a batch of pending events: SELECT ... FOR UPDATE SKIP LOCKED.
     * Concurrent publishers (the scheduler, the manual /publish-now endpoint, or another
     * instance) each get a disjoint batch instead of sending the same events twice.
     * Lock timeout -2 is Hibernate's SKIP_LOCKED.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2"))
    @Query("SELECT o FROM OutboxJpaEntity o WHERE o.status = :status ORDER BY o.createdAt ASC")
    List<OutboxJpaEntity> claimBatchByStatus(@Param("status") String status, Pageable pageable);

    List<OutboxJpaEntity> findTop50ByOrderByCreatedAtDesc();
}

