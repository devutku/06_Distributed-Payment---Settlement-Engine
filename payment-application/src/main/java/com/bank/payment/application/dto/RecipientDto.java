package com.bank.payment.application.dto;

import java.util.UUID;

public record RecipientDto(UUID accountId, String displayName, String maskedIban) {}
