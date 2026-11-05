package com.bank.payment.application.dto;

import com.bank.payment.domain.model.account.AccountStatus;

import java.math.BigDecimal;
import java.util.UUID;

public record AccountBalanceDto(
        UUID accountId,
        String customerNumber,
        BigDecimal balance,
        String currency,
        AccountStatus status,
        Long version
) {}

