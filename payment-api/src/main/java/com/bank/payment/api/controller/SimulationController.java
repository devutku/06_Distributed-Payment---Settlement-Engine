package com.bank.payment.api.controller;

import com.bank.payment.application.dto.InitiateTransferCommand;
import com.bank.payment.application.port.in.InitiateTransferUseCase;
import com.bank.payment.domain.model.account.AccountId;
import com.bank.payment.domain.model.shared.Currency;
import com.bank.payment.domain.model.shared.Money;
import com.bank.payment.domain.model.transfer.IdempotencyKey;
import com.bank.payment.infrastructure.persistence.entity.AccountJpaEntity;
import com.bank.payment.infrastructure.persistence.repository.SpringDataAccountRepository;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

@RestController
@RequestMapping("/api/v1/simulation")
public class SimulationController {

    private final InitiateTransferUseCase initiateTransferUseCase;
    private final SpringDataAccountRepository accountRepository;

    public SimulationController(
            InitiateTransferUseCase initiateTransferUseCase,
            SpringDataAccountRepository accountRepository) {
        this.initiateTransferUseCase = initiateTransferUseCase;
        this.accountRepository = accountRepository;
    }

    public record ConcurrencySimulationRequest(
            @NotNull UUID sourceAccountId,
            @NotNull UUID targetAccountId,
            @Min(2) @Max(200) int concurrencyCount,
            @NotNull @DecimalMin("0.01") BigDecimal amountPerTransfer
    ) {}

    public record ConcurrencySimulationResult(
            int totalRequests,
            int successfulRequests,
            int failedRequests,
            BigDecimal initialBalance,
            BigDecimal actualFinalBalance,
            boolean consistencyMaintained,
            long executionDurationMs,
            List<String> threadLogs
    ) {}

    @PostMapping("/concurrency")
    public ResponseEntity<ConcurrencySimulationResult> runSimulation(@RequestBody ConcurrencySimulationRequest req) {
        AccountJpaEntity initialSource = accountRepository.findById(req.sourceAccountId())
                .orElseThrow(() -> new IllegalArgumentException("Kaynak hesap bulunamadı"));

        BigDecimal initialBalance = initialSource.getBalance();
        int threads = req.concurrencyCount() > 0 ? req.concurrencyCount() : 10;
        BigDecimal amount = req.amountPerTransfer();

        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch readyLatch = new CountDownLatch(threads);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threads);

        List<String> threadLogs = Collections.synchronizedList(new ArrayList<>());
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failedCount = new AtomicInteger(0);

        long startTime = System.currentTimeMillis();

        for (int i = 1; i <= threads; i++) {
            final int index = i;
            final String idempotencyKey = "SIM-CONC-" + UUID.randomUUID();

            executor.submit(() -> {
                readyLatch.countDown();
                try {
                    startLatch.await(); // Wait for all threads to be simultaneously released
                    InitiateTransferCommand command = new InitiateTransferCommand(
                            IdempotencyKey.of(idempotencyKey),
                            AccountId.of(req.sourceAccountId()),
                            AccountId.of(req.targetAccountId()),
                            Money.of(amount, Currency.TRY),
                            "Eşzamanlılık Testi Thread #" + index
                    );

                    initiateTransferUseCase.execute(command);
                    successCount.incrementAndGet();
                    threadLogs.add("[Thread #" + index + "] SUCCESS: " + amount + " ₺ transfer edildi.");
                } catch (Exception ex) {
                    failedCount.incrementAndGet();
                    threadLogs.add("[Thread #" + index + "] BLOCKED: " + ex.getMessage());
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        try {
            readyLatch.await(10, TimeUnit.SECONDS);
            startLatch.countDown(); // Unleash all threads at the exact same instant!
            doneLatch.await(60, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            executor.shutdown();
        }

        long duration = System.currentTimeMillis() - startTime;

        AccountJpaEntity finalSource = accountRepository.findById(req.sourceAccountId())
                .orElseThrow(() -> new IllegalArgumentException("Kaynak hesap bulunamadı"));

        BigDecimal finalBalance = finalSource.getBalance();
        boolean consistent = finalBalance.compareTo(BigDecimal.ZERO) >= 0;

        return ResponseEntity.ok(new ConcurrencySimulationResult(
                threads,
                successCount.get(),
                failedCount.get(),
                initialBalance,
                finalBalance,
                consistent,
                duration,
                new ArrayList<>(threadLogs)
        ));
    }
}

