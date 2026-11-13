package com.bank.payment.api.config;

import com.bank.payment.domain.model.account.AccountStatus;
import com.bank.payment.domain.model.shared.Currency;
import com.bank.payment.infrastructure.persistence.entity.AccountJpaEntity;
import com.bank.payment.infrastructure.persistence.repository.SpringDataAccountRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
public class DataLoader implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(DataLoader.class);

    private final SpringDataAccountRepository accountRepository;

    public DataLoader(SpringDataAccountRepository accountRepository) {
        this.accountRepository = accountRepository;
    }

    private static final Map<UUID, String> SEED_OWNERS = Map.of(
            UUID.fromString("11111111-1111-1111-1111-111111111111"), "CUST-ALICE",
            UUID.fromString("22222222-2222-2222-2222-222222222222"), "CUST-BOB",
            UUID.fromString("33333333-3333-3333-3333-333333333333"), "CUST-CHARLIE");

    /** Demo backfill: idempotent, only fills owners that are still null. */
    private void backfillOwners() {
        SEED_OWNERS.forEach((id, owner) -> accountRepository.findById(id).ifPresent(account -> {
            if (account.getOwnerCustomerId() == null) {
                account.setOwnerCustomerId(owner);
                accountRepository.save(account);
                log.info("Backfilled owner {} on seeded account {}", owner, id);
            }
        }));
    }

    @Override
    public void run(String... args) {
        if (accountRepository.count() == 0) {
            log.info("Seeding initial banking accounts for Clean Architecture Payment Engine...");

            AccountJpaEntity alice = new AccountJpaEntity(
                    UUID.fromString("11111111-1111-1111-1111-111111111111"),
                    "TR330006100511123456780001 (Alice Smith)",
                    new BigDecimal("10000.00"),
                    Currency.TRY,
                    AccountStatus.ACTIVE,
                    0L,
                    "CUST-ALICE"
            );

            AccountJpaEntity bob = new AccountJpaEntity(
                    UUID.fromString("22222222-2222-2222-2222-222222222222"),
                    "TR330006100511123456780002 (Bob Jones)",
                    new BigDecimal("5000.00"),
                    Currency.TRY,
                    AccountStatus.ACTIVE,
                    0L,
                    "CUST-BOB"
            );

            AccountJpaEntity charlie = new AccountJpaEntity(
                    UUID.fromString("33333333-3333-3333-3333-333333333333"),
                    "TR330006100511123456780003 (Charlie Brown)",
                    new BigDecimal("250.00"), // Low balance for race-condition stress tests
                    Currency.TRY,
                    AccountStatus.ACTIVE,
                    0L,
                    "CUST-CHARLIE"
            );

            AccountJpaEntity clearing = new AccountJpaEntity(
                    UUID.fromString("99999999-9999-9999-9999-999999999999"),
                    "TR330006100511123456780099 (Clearing Treasury)",
                    new BigDecimal("1000000.00"),
                    Currency.TRY,
                    AccountStatus.ACTIVE,
                    0L,
                    null
            );

            accountRepository.saveAll(List.of(alice, bob, charlie, clearing));
            log.info("Successfully seeded 4 initial accounts.");
        } else {
            backfillOwners();
        }
    }
}

