package com.bank.payment.application.dto;

import com.bank.payment.domain.model.transfer.PaymentGatewayType;
import com.bank.payment.domain.model.transfer.TransferStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record TransferSummaryDto(
        UUID transferId,
        UUID sourceAccountId,
        UUID targetAccountId,
        BigDecimal amount,
        String currency,
        TransferStatus status,
        PaymentGatewayType gatewayType,
        Instant createdAt
) {}
