package com.bank.payment.infrastructure.persistence.adapter;

import com.bank.payment.application.dto.AccountStatementDto;
import com.bank.payment.application.port.out.LoadLedgerPort;
import com.bank.payment.domain.model.account.AccountId;
import com.bank.payment.infrastructure.persistence.repository.SpringDataLedgerRepository;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class LedgerQueryAdapter implements LoadLedgerPort {

    private final SpringDataLedgerRepository ledgerRepository;

    public LedgerQueryAdapter(SpringDataLedgerRepository ledgerRepository) {
        this.ledgerRepository = ledgerRepository;
    }

    @Override
    public List<AccountStatementDto.LedgerItem> loadEntries(AccountId id) {
        return ledgerRepository.findByAccountIdOrderByCreatedAtDesc(id.value()).stream()
                .map(e -> new AccountStatementDto.LedgerItem(
                        e.getId(),
                        e.getTransferId(),
                        e.getEntryType(),
                        e.getAmount(),
                        e.getBalanceAfter(),
                        e.getDescription(),
                        e.getCreatedAt()))
                .toList();
    }
}
