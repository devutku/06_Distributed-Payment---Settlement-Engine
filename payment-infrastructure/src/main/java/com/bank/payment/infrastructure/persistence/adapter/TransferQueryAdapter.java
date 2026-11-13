package com.bank.payment.infrastructure.persistence.adapter;

import com.bank.payment.application.dto.TransferSummaryDto;
import com.bank.payment.application.port.out.LoadTransfersPort;
import com.bank.payment.domain.model.account.AccountId;
import com.bank.payment.infrastructure.persistence.entity.TransferJpaEntity;
import com.bank.payment.infrastructure.persistence.repository.SpringDataTransferRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

@Component
public class TransferQueryAdapter implements LoadTransfersPort {

    private final SpringDataTransferRepository transferRepository;

    public TransferQueryAdapter(SpringDataTransferRepository transferRepository) {
        this.transferRepository = transferRepository;
    }

    @Override
    public List<TransferSummaryDto> loadRecent(int limit) {
        return transferRepository.findAllByOrderByCreatedAtDesc(PageRequest.of(0, limit)).stream()
                .map(TransferQueryAdapter::toSummary)
                .toList();
    }

    @Override
    public List<TransferSummaryDto> loadRecentInvolving(Collection<AccountId> ids, int limit) {
        if (ids.isEmpty()) {
            return List.of();
        }
        List<UUID> uuids = ids.stream().map(AccountId::value).toList();
        return transferRepository.findInvolvingAccounts(uuids, PageRequest.of(0, limit)).stream()
                .map(TransferQueryAdapter::toSummary)
                .toList();
    }

    private static TransferSummaryDto toSummary(TransferJpaEntity t) {
        return new TransferSummaryDto(
                t.getId(),
                t.getSourceAccountId(),
                t.getTargetAccountId(),
                t.getAmount(),
                t.getCurrency().name(),
                t.getStatus(),
                t.getGatewayType(),
                t.getCreatedAt());
    }
}
