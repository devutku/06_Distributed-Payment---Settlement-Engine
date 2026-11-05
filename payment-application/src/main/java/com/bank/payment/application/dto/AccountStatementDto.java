package com.bank.payment.application.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record AccountStatementDto(
        UUID accountId,
        String customerNumber,
        BigDecimal currentBalance,
        String currency,
        List<LedgerItem> items
) {
    public record LedgerItem(
            UUID entryId,
            UUID transferId,
            String type, // DEBIT, CREDIT
            BigDecimal amount,
            BigDecimal balanceAfter,
            String description,
            Instant createdAt
    ) {}
}

