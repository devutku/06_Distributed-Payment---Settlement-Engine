package com.bank.payment.infrastructure.persistence.adapter;

import com.bank.payment.application.port.out.TransactionPort;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.function.Supplier;

/**
 * Spring implementation of {@link TransactionPort}.
 * REQUIRES_NEW guarantees the work commits before this method returns,
 * even if a caller accidentally wraps the use case in an outer transaction.
 */
@Component
public class SpringTransactionAdapter implements TransactionPort {

    private final TransactionTemplate transactionTemplate;

    public SpringTransactionAdapter(PlatformTransactionManager transactionManager) {
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.transactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.transactionTemplate.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    @Override
    public <T> T executeInNewTransaction(Supplier<T> work) {
        return transactionTemplate.execute(status -> work.get());
    }
}
