package com.bank.payment.infrastructure.persistence.adapter;

import com.bank.payment.application.port.out.LoadAccountPort;
import com.bank.payment.application.port.out.UpdateAccountBalancePort;
import com.bank.payment.domain.model.account.Account;
import com.bank.payment.domain.model.account.AccountId;
import com.bank.payment.infrastructure.persistence.entity.AccountJpaEntity;
import com.bank.payment.infrastructure.persistence.repository.SpringDataAccountRepository;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

@Component
public class AccountPersistenceAdapter implements LoadAccountPort, UpdateAccountBalancePort {

    private final SpringDataAccountRepository accountRepository;

    public AccountPersistenceAdapter(SpringDataAccountRepository accountRepository) {
        this.accountRepository = accountRepository;
    }

    @Override
    public Optional<Account> loadAccount(AccountId id) {
        return accountRepository.findById(id.value())
                .map(AccountJpaEntity::toDomain);
    }

    @Override
    public Optional<Account> loadAccountWithLock(AccountId id) {
        return accountRepository.findByIdWithPessimisticLock(id.value())
                .map(AccountJpaEntity::toDomain);
    }

    @Override
    public List<Account> loadAllAccounts() {
        return accountRepository.findAll().stream()
                .map(AccountJpaEntity::toDomain)
                .toList();
    }

    @Override
    public void updateAccount(Account account) {
        AccountJpaEntity entity = AccountJpaEntity.fromDomain(account);
        accountRepository.save(entity);
    }
}
