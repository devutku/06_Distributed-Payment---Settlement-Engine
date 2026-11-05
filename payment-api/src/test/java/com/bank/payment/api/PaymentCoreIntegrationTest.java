package com.bank.payment.api;

import com.bank.payment.api.controller.GatewayPaymentController;
import com.bank.payment.api.controller.TransferController;
import com.bank.payment.domain.model.shared.Currency;
import com.bank.payment.domain.model.transfer.PaymentGatewayType;
import com.bank.payment.infrastructure.persistence.repository.SpringDataAccountRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.support.SendResult;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
@Import(PaymentCoreIntegrationTest.TestKafkaConfig.class)
class PaymentCoreIntegrationTest {

    @TestConfiguration
    static class TestKafkaConfig {
        @Bean
        @Primary
        @SuppressWarnings("unchecked")
        public KafkaTemplate<String, String> testKafkaTemplate() {
            ProducerFactory<String, String> pf = mock(ProducerFactory.class);
            return new KafkaTemplate<>(pf) {
                @Override
                public CompletableFuture<SendResult<String, String>> send(String topic, String key, String data) {
                    return CompletableFuture.completedFuture(null);
                }
            };
        }
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private SpringDataAccountRepository accountRepository;

    @Test
    @DisplayName("Transfer should succeed and produce SETTLED status")
    void testTransferSuccess() throws Exception {
        UUID aliceId = UUID.fromString("11111111-1111-1111-1111-111111111111");
        UUID bobId = UUID.fromString("22222222-2222-2222-2222-222222222222");

        TransferController.TransferRequest req = new TransferController.TransferRequest(
                aliceId,
                bobId,
                new BigDecimal("150.00"),
                Currency.TRY,
                "Integration Test Transfer"
        );

        String idempotencyKey = "TEST-IDEMP-" + UUID.randomUUID();

        // 1. Initial Transfer Request
        mockMvc.perform(post("/api/v1/transfers")
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SETTLED"))
                .andExpect(jsonPath("$.idempotentReplay").value(false));

        // 2. Exact Duplicate Replay: Should return cached result with idempotentReplay=true
        mockMvc.perform(post("/api/v1/transfers")
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SETTLED"))
                .andExpect(jsonPath("$.idempotentReplay").value(true));
    }

    @Test
    @DisplayName("Transfer without Idempotency-Key header should be rejected with 400 Bad Request by IdempotencyFilter")
    void testMissingIdempotencyHeader() throws Exception {
        TransferController.TransferRequest req = new TransferController.TransferRequest(
                UUID.randomUUID(),
                UUID.randomUUID(),
                new BigDecimal("50.00"),
                Currency.TRY,
                "Test"
        );

        mockMvc.perform(post("/api/v1/transfers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Missing Idempotency-Key Header"));
    }

    @Test
    @DisplayName("Transfer exceeding account balance should return 422 ProblemDetail")
    void testInsufficientFunds() throws Exception {
        UUID charlieId = UUID.fromString("33333333-3333-3333-3333-333333333333");
        UUID bobId = UUID.fromString("22222222-2222-2222-2222-222222222222");

        TransferController.TransferRequest req = new TransferController.TransferRequest(
                charlieId,
                bobId,
                new BigDecimal("999999.00"),
                Currency.TRY,
                "Overdraft Transfer"
        );

        mockMvc.perform(post("/api/v1/transfers")
                        .header("Idempotency-Key", "IDEMP-OVERDRAFT-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.title").value("Insufficient Funds"));
    }

    @Test
    @DisplayName("Full End-to-End Payment Gateway & Webhook Lifecycle matching diagram")
    void testPaymentGatewayAndWebhookFlow() throws Exception {
        UUID aliceId = UUID.fromString("11111111-1111-1111-1111-111111111111");
        UUID bobId = UUID.fromString("22222222-2222-2222-2222-222222222222");

        // 1. User Clicks Pay -> Payment Request with Idempotency Key -> Gateway AUTHORIZED
        GatewayPaymentController.GatewayPayRequest payReq = new GatewayPaymentController.GatewayPayRequest(
                aliceId,
                bobId,
                new BigDecimal("200.00"),
                Currency.TRY,
                PaymentGatewayType.STRIPE,
                "alice@example.com",
                "E-Commerce Order #8821"
        );

        String idempotencyKey = "PSP-IDEMP-" + UUID.randomUUID();

        MvcResult result = mockMvc.perform(post("/api/v1/gateway/pay")
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("AUTHORIZED"))
                .andExpect(jsonPath("$.externalReferenceId").exists())
                .andReturn();

        String responseBody = result.getResponse().getContentAsString();
        Map<String, Object> respMap = objectMapper.readValue(responseBody, Map.class);
        String externalRef = (String) respMap.get("externalReferenceId");

        // 2. Gateway triggers CAPTURED Webhook -> Webhook Listener receives and processes
        mockMvc.perform(post("/api/v1/webhooks/gateway/simulate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "externalReferenceId", externalRef,
                                "amount", 200.00,
                                "currency", "TRY"
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SETTLED"))
                .andExpect(jsonPath("$.processed").value(true));
    }

    @Test
    @DisplayName("Accounts list endpoint should return seeded accounts")
    void testGetAccounts() throws Exception {
        mockMvc.perform(get("/api/v1/accounts"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(4));
    }
}
