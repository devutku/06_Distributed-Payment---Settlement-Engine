package com.bank.payment.api.controller;

import com.bank.payment.application.dto.AccountBalanceDto;
import com.bank.payment.application.dto.AccountStatementDto;
import com.bank.payment.application.port.in.GetAccountBalanceQuery;
import com.bank.payment.domain.model.account.AccountId;
import com.bank.payment.domain.model.account.AccountStatus;
import com.bank.payment.infrastructure.persistence.entity.AccountJpaEntity;
import com.bank.payment.infrastructure.persistence.entity.LedgerJpaEntity;
import com.bank.payment.infrastructure.persistence.repository.SpringDataAccountRepository;
import com.bank.payment.infrastructure.persistence.repository.SpringDataLedgerRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/accounts")
public class AccountController {

    private final GetAccountBalanceQuery getAccountBalanceQuery;
    private final SpringDataAccountRepository accountRepository;
    private final SpringDataLedgerRepository ledgerRepository;

    public AccountController(
            GetAccountBalanceQuery getAccountBalanceQuery,
            SpringDataAccountRepository accountRepository,
            SpringDataLedgerRepository ledgerRepository) {
        this.getAccountBalanceQuery = getAccountBalanceQuery;
        this.accountRepository = accountRepository;
        this.ledgerRepository = ledgerRepository;
    }

    @GetMapping
    public ResponseEntity<List<AccountBalanceDto>> getAllAccounts() {
        return ResponseEntity.ok(getAccountBalanceQuery.getAllAccounts());
    }

    @GetMapping("/{id}")
    public ResponseEntity<AccountBalanceDto> getAccountById(@PathVariable UUID id) {
        return ResponseEntity.ok(getAccountBalanceQuery.getAccountById(AccountId.of(id)));
    }

    @GetMapping("/{id}/statement")
    public ResponseEntity<AccountStatementDto> getStatement(@PathVariable UUID id) {
        AccountJpaEntity acc = accountRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Hesap bulunamadı: " + id));

        List<LedgerJpaEntity> entries = ledgerRepository.findByAccountIdOrderByCreatedAtDesc(id);

        List<AccountStatementDto.LedgerItem> items = entries.stream()
                .map(e -> new AccountStatementDto.LedgerItem(
                        e.getId(),
                        e.getTransferId(),
                        e.getEntryType(),
                        e.getAmount(),
                        e.getBalanceAfter(),
                        e.getDescription(),
                        e.getCreatedAt()
                ))
                .toList();

        return ResponseEntity.ok(new AccountStatementDto(
                acc.getId(),
                acc.getCustomerNumber(),
                acc.getBalance(),
                acc.getCurrency().name(),
                items
        ));
    }

    @PostMapping("/reset")
    public ResponseEntity<Map<String, String>> resetBalances() {
        List<AccountJpaEntity> accounts = accountRepository.findAll();
        for (AccountJpaEntity acc : accounts) {
            if (acc.getCustomerNumber().contains("Alice")) {
                acc.setBalance(new BigDecimal("10000.00"));
            } else if (acc.getCustomerNumber().contains("Bob")) {
                acc.setBalance(new BigDecimal("5000.00"));
            } else if (acc.getCustomerNumber().contains("Charlie")) {
                acc.setBalance(new BigDecimal("250.00"));
            } else if (acc.getCustomerNumber().contains("Clearing")) {
                acc.setBalance(new BigDecimal("1000000.00"));
            }
            acc.setStatus(AccountStatus.ACTIVE);
        }
        accountRepository.saveAll(accounts);
        return ResponseEntity.ok(Map.of("message", "Hesap bakiyeleri başarıyla sıfırlandı"));
    }
}
