package com.bank.payment.api.controller;

import com.bank.payment.application.dto.InitiateTransferCommand;
import com.bank.payment.api.security.RequesterResolver;
import com.bank.payment.application.dto.TransferResultDto;
import com.bank.payment.application.dto.TransferSummaryDto;
import com.bank.payment.application.port.in.GetRecentTransfersQuery;
import com.bank.payment.application.port.in.InitiateTransferUseCase;
import com.bank.payment.domain.model.account.AccountId;
import com.bank.payment.domain.model.shared.Currency;
import com.bank.payment.domain.model.shared.Money;
import com.bank.payment.domain.model.transfer.IdempotencyKey;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Timer;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/transfers")
public class TransferController {

    private final InitiateTransferUseCase initiateTransferUseCase;
    private final GetRecentTransfersQuery getRecentTransfersQuery;
    private final RequesterResolver requesterResolver;
    private final Timer paymentTransferTimer;
    private final Counter paymentSuccessCounter;
    private final Counter paymentFailureCounter;

    public TransferController(
            InitiateTransferUseCase initiateTransferUseCase,
            GetRecentTransfersQuery getRecentTransfersQuery,
            RequesterResolver requesterResolver,
            Timer paymentTransferTimer,
            Counter paymentSuccessCounter,
            Counter paymentFailureCounter) {
        this.initiateTransferUseCase = initiateTransferUseCase;
        this.getRecentTransfersQuery = getRecentTransfersQuery;
        this.requesterResolver = requesterResolver;
        this.paymentTransferTimer = paymentTransferTimer;
        this.paymentSuccessCounter = paymentSuccessCounter;
        this.paymentFailureCounter = paymentFailureCounter;
    }

    public record TransferRequest(
            @NotNull(message = "sourceAccountId zorunludur") UUID sourceAccountId,
            @NotNull(message = "targetAccountId zorunludur") UUID targetAccountId,
            @NotNull(message = "amount zorunludur") @DecimalMin(value = "0.01", message = "Tutar 0'dan büyük olmalıdır") BigDecimal amount,
            Currency currency,
            String description
    ) {}

    @PostMapping
    public ResponseEntity<TransferResultDto> transfer(
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKeyHeader,
            @Valid @RequestBody TransferRequest request,
            Authentication auth) {

        String key = idempotencyKeyHeader != null ? idempotencyKeyHeader : UUID.randomUUID().toString();
        Currency curr = request.currency() != null ? request.currency() : Currency.TRY;

        InitiateTransferCommand command = new InitiateTransferCommand(
                IdempotencyKey.of(key),
                AccountId.of(request.sourceAccountId()),
                AccountId.of(request.targetAccountId()),
                Money.of(request.amount(), curr),
                request.description(),
                requesterResolver.resolve(auth)
        );

        return paymentTransferTimer.record(() -> {
            try {
                TransferResultDto result = initiateTransferUseCase.execute(command);
                paymentSuccessCounter.increment();
                return ResponseEntity.ok(result);
            } catch (Exception ex) {
                paymentFailureCounter.increment();
                throw ex;
            }
        });
    }

    @GetMapping
    public ResponseEntity<List<TransferSummaryDto>> getRecentTransfers(Authentication auth) {
        return ResponseEntity.ok(getRecentTransfersQuery.getRecentTransfers(requesterResolver.resolve(auth)));
    }
}
