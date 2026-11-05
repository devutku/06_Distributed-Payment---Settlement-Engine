package com.bank.payment.infrastructure.gateway;

import com.bank.payment.application.port.out.PaymentGatewayPort;
import com.bank.payment.domain.model.transfer.PaymentGatewayType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

@Component
public class MockPaymentGatewayAdapter implements PaymentGatewayPort {

    private static final Logger log = LoggerFactory.getLogger(MockPaymentGatewayAdapter.class);

    private final String webhookSecret;

    public MockPaymentGatewayAdapter(@Value("${payment.gateway.webhook-secret:whsec_fintech_production_secret_key_99}") String webhookSecret) {
        this.webhookSecret = webhookSecret;
    }

    @Override
    public GatewayAuthorizationResponse authorizePayment(GatewayAuthorizationRequest request) {
        log.info("Contacting external payment gateway [{}] for transfer: {}", request.gatewayType(), request.transferId());

        String prefix = switch (request.gatewayType()) {
            case STRIPE -> "pi_stripe_";
            case PAYPAL -> "pay_paypal_";
            case RAZORPAY -> "order_rzp_";
            default -> "ext_ref_";
        };

        String externalRef = prefix + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        String clientSecret = externalRef + "_secret_" + UUID.randomUUID().toString().substring(0, 8);

        log.info("Gateway [{}] AUTHORIZED payment successfully. Ref: {}", request.gatewayType(), externalRef);

        return new GatewayAuthorizationResponse(
                true,
                externalRef,
                clientSecret,
                "Payment authorized by external gateway provider"
        );
    }

    @Override
    public boolean verifySignature(String payload, String signatureHeader) {
        if (signatureHeader == null || signatureHeader.isBlank() || payload == null) {
            return false;
        }

        try {
            String calculatedSignature = computeHmacSha256(payload, webhookSecret);
            // Constant-time string comparison to prevent timing attacks
            return MessageDigestUtils.constantTimeEquals(calculatedSignature, signatureHeader);
        } catch (Exception ex) {
            log.error("Webhook signature verification failed: {}", ex.getMessage());
            return false;
        }
    }

    public String computeHmacSha256(String data, String key) {
        try {
            Mac sha256Hmac = Mac.getInstance("HmacSHA256");
            SecretKeySpec secretKey = new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
            sha256Hmac.init(secretKey);
            byte[] signedBytes = sha256Hmac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(signedBytes);
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new RuntimeException("HMAC computation failed", e);
        }
    }

    public String getWebhookSecret() {
        return webhookSecret;
    }

    private static class MessageDigestUtils {
        public static boolean constantTimeEquals(String a, String b) {
            if (a == null || b == null) {
                return false;
            }
            if (a.length() != b.length()) {
                return false;
            }
            int result = 0;
            for (int i = 0; i < a.length(); i++) {
                result |= a.charAt(i) ^ b.charAt(i);
            }
            return result == 0;
        }
    }
}

