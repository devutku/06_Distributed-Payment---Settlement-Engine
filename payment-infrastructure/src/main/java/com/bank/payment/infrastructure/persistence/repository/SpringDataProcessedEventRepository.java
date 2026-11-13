package com.bank.payment.infrastructure.persistence.repository;

import com.bank.payment.infrastructure.persistence.entity.ProcessedEventJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface SpringDataProcessedEventRepository extends JpaRepository<ProcessedEventJpaEntity, String> {
}
