package com.bank.payment.application.port.out;

import com.bank.payment.domain.event.DomainEvent;

public interface OutboxEventPort {
    void saveEvent(DomainEvent event);
}
