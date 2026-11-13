package com.bank.payment.application.port.out;

import java.util.function.Supplier;

/**
 * Outbound port for database transaction boundaries.
 * Keeps the application layer free of Spring while letting a use case
 * decide exactly where a transaction starts and when it has committed.
 */
public interface TransactionPort {

    /**
     * Runs {@code work} in its own transaction and returns only after that
     * transaction has committed. If the work or the commit fails, the
     * exception propagates and nothing is persisted.
     */
    <T> T executeInNewTransaction(Supplier<T> work);
}
