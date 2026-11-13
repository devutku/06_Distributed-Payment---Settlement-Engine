package com.bank.payment.application.security;

/** Raised when a webhook's HMAC signature does not verify; mapped to 401 at the API edge. */
public class InvalidWebhookSignatureException extends RuntimeException {
    public InvalidWebhookSignatureException(String message) {
        super(message);
    }
}
