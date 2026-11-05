package com.bank.payment.application.dto;

import com.bank.payment.domain.model.transfer.TransferStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record TransferResultDto(
        UUID transferId,
        String idempotencyKey,
        UUID sourceAccountId,
        UUID targetAccountId,
        BigDecimal amount,
        String currency,
        TransferStatus status,
        String message,
        Instant timestamp,
        boolean idempotentReplay
) {}

