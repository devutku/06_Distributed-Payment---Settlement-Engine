package com.bank.payment.application.port.in;

import com.bank.payment.application.dto.RecipientDto;
import com.bank.payment.application.security.Requester;

import java.util.List;

public interface GetRecipientsQuery {
    List<RecipientDto> getRecipients(Requester requester);
}
