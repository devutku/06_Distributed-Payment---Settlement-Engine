package com.bank.payment.api.controller;

import com.bank.payment.api.security.RequesterResolver;
import com.bank.payment.application.dto.AccountBalanceDto;
import com.bank.payment.application.dto.AccountStatementDto;
import com.bank.payment.application.dto.RecipientDto;
import com.bank.payment.application.port.in.GetAccountBalanceQuery;
import com.bank.payment.application.port.in.GetAccountStatementQuery;
import com.bank.payment.application.port.in.GetRecipientsQuery;
import com.bank.payment.domain.model.account.AccountId;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/accounts")
public class AccountController {

    private final GetAccountBalanceQuery getAccountBalanceQuery;
    private final GetAccountStatementQuery getAccountStatementQuery;
    private final GetRecipientsQuery getRecipientsQuery;
    private final RequesterResolver requesterResolver;

    public AccountController(
            GetAccountBalanceQuery getAccountBalanceQuery,
            GetAccountStatementQuery getAccountStatementQuery,
            GetRecipientsQuery getRecipientsQuery,
            RequesterResolver requesterResolver) {
        this.getAccountBalanceQuery = getAccountBalanceQuery;
        this.getAccountStatementQuery = getAccountStatementQuery;
        this.getRecipientsQuery = getRecipientsQuery;
        this.requesterResolver = requesterResolver;
    }

    @GetMapping
    public ResponseEntity<List<AccountBalanceDto>> getAccounts(Authentication auth) {
        return ResponseEntity.ok(getAccountBalanceQuery.getAccounts(requesterResolver.resolve(auth)));
    }

    @GetMapping("/recipients")
    public ResponseEntity<List<RecipientDto>> getRecipients(Authentication auth) {
        return ResponseEntity.ok(getRecipientsQuery.getRecipients(requesterResolver.resolve(auth)));
    }

    @GetMapping("/{id:[0-9a-fA-F-]{36}}")
    public ResponseEntity<AccountBalanceDto> getAccountById(@PathVariable UUID id, Authentication auth) {
        return ResponseEntity.ok(getAccountBalanceQuery.getAccount(requesterResolver.resolve(auth), AccountId.of(id)));
    }

    @GetMapping("/{id:[0-9a-fA-F-]{36}}/statement")
    public ResponseEntity<AccountStatementDto> getStatement(@PathVariable UUID id, Authentication auth) {
        return ResponseEntity.ok(getAccountStatementQuery.getStatement(requesterResolver.resolve(auth), AccountId.of(id)));
    }
}
