package com.bank.payment.infrastructure.persistence.repository;

import com.bank.payment.infrastructure.persistence.entity.OutboxJpaEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface SpringDataOutboxRepository extends JpaRepository<OutboxJpaEntity, UUID> {
    @Query("SELECT o FROM OutboxJpaEntity o WHERE o.status = :status ORDER BY o.createdAt ASC")
    List<OutboxJpaEntity> findByStatusOrderByCreatedAtAsc(@Param("status") String status, Pageable pageable);

    List<OutboxJpaEntity> findTop50ByOrderByCreatedAtDesc();
}
