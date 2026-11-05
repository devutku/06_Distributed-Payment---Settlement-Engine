package com.bank.payment.application.port.out;

import com.bank.payment.application.dto.TransferResultDto;
import com.bank.payment.domain.model.transfer.IdempotencyKey;

import java.util.Optional;

public interface IdempotencyPort {
    boolean acquireLock(IdempotencyKey key);
    void releaseLock(IdempotencyKey key);
    Optional<TransferResultDto> getCachedResult(IdempotencyKey key);
    void cacheResult(IdempotencyKey key, TransferResultDto result);
}

