package com.bank.payment.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
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
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
@Import(BusinessMetricsIntegrationTest.TestKafkaConfig.class)
class BusinessMetricsIntegrationTest {

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

    private static final String ALICE_ACC = "11111111-1111-1111-1111-111111111111";
    private static final String BOB_ACC = "22222222-2222-2222-2222-222222222222";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private MeterRegistry registry;

    private static RequestPostProcessor alice() {
        return jwt().jwt(j -> j.claim("customer_id", "CUST-ALICE"))
                .authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER"));
    }

    private double count(String name, String outcome) {
        Counter c = registry.find(name).tag("outcome", outcome).counter();
        return c == null ? 0 : c.count();
    }

    private String transfer(String amount) throws Exception {
        return objectMapper.writeValueAsString(Map.of(
                "sourceAccountId", ALICE_ACC, "targetAccountId", BOB_ACC,
                "amount", amount, "currency", "TRY", "description", "metrics test"));
    }

    @Test
    @DisplayName("A settled transfer counts as success; repeating its key counts as replay")
    void successAndReplayAreCounted() throws Exception {
        double success = count("mutabik.transfers", "success");
        double replay = count("mutabik.transfers", "replay");
        String key = "METRICS-" + UUID.randomUUID();

        for (int i = 0; i < 2; i++) {
            mockMvc.perform(post("/api/v1/transfers").with(alice()).header("Idempotency-Key", key)
                            .contentType(MediaType.APPLICATION_JSON).content(transfer("1.00")))
                    .andExpect(status().isOk());
        }

        assertThat(count("mutabik.transfers", "success") - success).isEqualTo(1.0);
        assertThat(count("mutabik.transfers", "replay") - replay).isEqualTo(1.0);
    }

    @Test
    @DisplayName("Insufficient funds is a business rejection, not an error (keeps the error-rate alert quiet)")
    void insufficientFundsIsNotAnError() throws Exception {
        double insufficient = count("mutabik.transfers", "insufficient_funds");
        double error = count("mutabik.transfers", "error");

        mockMvc.perform(post("/api/v1/transfers").with(alice()).header("Idempotency-Key", "METRICS-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON).content(transfer("99999999.00")))
                .andExpect(status().isUnprocessableEntity());

        assertThat(count("mutabik.transfers", "insufficient_funds") - insufficient).isEqualTo(1.0);
        assertThat(count("mutabik.transfers", "error") - error).isZero();
    }

    @Test
    @DisplayName("A webhook with a bad signature is counted as invalid_signature")
    void invalidWebhookSignatureIsCounted() throws Exception {
        double invalid = count("mutabik.webhooks", "invalid_signature");
        String body = objectMapper.writeValueAsString(Map.of(
                "eventType", "payment_intent.succeeded", "externalReferenceId", "pi_unknown",
                "amount", "1.00", "currency", "TRY"));

        mockMvc.perform(post("/api/v1/webhooks/gateway").header("X-Signature", "deadbeef")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());

        assertThat(count("mutabik.webhooks", "invalid_signature") - invalid).isEqualTo(1.0);
    }

    @Test
    @DisplayName("The outbox backlog gauge is registered")
    void outboxPendingGaugeIsRegistered() {
        assertThat(registry.find("mutabik.outbox.pending").gauge()).isNotNull();
    }
}
