package com.bank.payment.api;

import com.bank.payment.api.security.KeycloakRoleConverter;
import com.bank.payment.api.security.SecurityConfig;
import com.bank.payment.infrastructure.persistence.repository.SpringDataAccountRepository;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import com.bank.payment.infrastructure.gateway.MockPaymentGatewayAdapter;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
@Import(SecurityIntegrationTest.TestKafkaConfig.class)
class SecurityIntegrationTest {

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
    private static final String CLEARING_ACC = "99999999-9999-9999-9999-999999999999";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private MockPaymentGatewayAdapter gatewayAdapter;
    @Autowired
    private SpringDataAccountRepository accountRepository;

    private static RequestPostProcessor customer(String customerId) {
        return jwt().jwt(j -> j.claim("customer_id", customerId))
                .authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER"));
    }

    private static RequestPostProcessor alice() { return customer("CUST-ALICE"); }

    private static RequestPostProcessor bob() { return customer("CUST-BOB"); }

    private static RequestPostProcessor admin() {
        return jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
    }

    private String transferBody(String source, String target) throws Exception {
        return objectMapper.writeValueAsString(Map.of(
                "sourceAccountId", source, "targetAccountId", target,
                "amount", "1.00", "currency", "TRY", "description", "sec test"));
    }

    @Test
    void noTokenIs401WithProblemJson() throws Exception {
        mockMvc.perform(get("/api/v1/accounts"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(401));
    }

    @Test
    void customerTokenWithoutCustomerIdIs401() throws Exception {
        mockMvc.perform(get("/api/v1/accounts")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER"))))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
    }

    @Test
    void wrongAudienceOrIssuerIs401() {
        OAuth2TokenValidator<Jwt> validator = SecurityConfig.tokenValidator(
                "http://localhost:8180/realms/mutabik", "mutabik-api");
        Instant now = Instant.now();

        Jwt good = Jwt.withTokenValue("t").header("alg", "RS256")
                .issuer("http://localhost:8180/realms/mutabik").audience(List.of("mutabik-api"))
                .issuedAt(now).expiresAt(now.plusSeconds(60)).build();
        Jwt wrongAud = Jwt.withTokenValue("t").header("alg", "RS256")
                .issuer("http://localhost:8180/realms/mutabik").audience(List.of("other"))
                .issuedAt(now).expiresAt(now.plusSeconds(60)).build();
        Jwt wrongIss = Jwt.withTokenValue("t").header("alg", "RS256")
                .issuer("http://keycloak:8080/realms/mutabik").audience(List.of("mutabik-api"))
                .issuedAt(now).expiresAt(now.plusSeconds(60)).build();

        assertThat(validator.validate(good).hasErrors()).isFalse();
        assertThat(validator.validate(wrongAud).hasErrors()).isTrue();
        assertThat(validator.validate(wrongIss).hasErrors()).isTrue();
    }

    @Test
    void aliceCannotTransferFromBobsAccount() throws Exception {
        mockMvc.perform(post("/api/v1/transfers").with(alice())
                        .header("Idempotency-Key", "K-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(transferBody(BOB_ACC, ALICE_ACC)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Account Not Found"));
    }

    @Test
    void aliceCannotReadBobsStatement() throws Exception {
        mockMvc.perform(get("/api/v1/accounts/" + BOB_ACC + "/statement").with(alice()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Account Not Found"));
    }

    @Test
    void unknownAccountIs404() throws Exception {
        mockMvc.perform(get("/api/v1/accounts/" + UUID.randomUUID() + "/statement").with(alice()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Account Not Found"));
    }

    @Test
    void aliceListsOnlyHerAccount() throws Exception {
        mockMvc.perform(get("/api/v1/accounts").with(alice()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].accountId").value(ALICE_ACC));
    }

    @Test
    void adminListsAllAccounts() throws Exception {
        mockMvc.perform(get("/api/v1/accounts").with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(4));
    }

    @Test
    void aliceCannotSendToClearing() throws Exception {
        mockMvc.perform(post("/api/v1/transfers").with(alice())
                        .header("Idempotency-Key", "K-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(transferBody(ALICE_ACC, CLEARING_ACC)))
                .andExpect(status().isNotFound());
    }

    @Test
    void customerCannotUseOutbox() throws Exception {
        mockMvc.perform(get("/api/v1/outbox").with(alice()))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
    }

    @Test
    void adminCannotTransfer() throws Exception {
        mockMvc.perform(post("/api/v1/transfers").with(admin())
                        .header("Idempotency-Key", "K-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(transferBody(ALICE_ACC, BOB_ACC)))
                .andExpect(status().isForbidden());
    }

    @Test
    void adminCannotPay() throws Exception {
        String body = objectMapper.writeValueAsString(Map.of(
                "customerAccountId", ALICE_ACC, "merchantAccountId", BOB_ACC, "amount", "1.00"));
        mockMvc.perform(post("/api/v1/gateway/pay").with(admin())
                        .header("Idempotency-Key", "K-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isForbidden());
    }

    @Test
    void removedDemoEndpointsAre404() throws Exception {
        mockMvc.perform(post("/api/v1/accounts/reset").with(admin()))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/v1/simulation/concurrency").with(admin())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/v1/webhooks/gateway/simulate").with(admin())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void webhookWorksWithoutToken() throws Exception {
        String payBody = objectMapper.writeValueAsString(Map.of(
                "customerAccountId", ALICE_ACC, "merchantAccountId", BOB_ACC,
                "amount", "12.00", "currency", "TRY"));
        MvcResult pay = mockMvc.perform(post("/api/v1/gateway/pay").with(alice())
                        .header("Idempotency-Key", "PSP-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payBody))
                .andExpect(status().isOk())
                .andReturn();
        String ref = (String) objectMapper.readValue(pay.getResponse().getContentAsString(), Map.class)
                .get("externalReferenceId");

        String payload = objectMapper.writeValueAsString(Map.of(
                "eventType", "payment_intent.succeeded", "externalReferenceId", ref,
                "amount", "12.00", "currency", "TRY"));
        String sig = gatewayAdapter.computeHmacSha256(payload, gatewayAdapter.getWebhookSecret());

        mockMvc.perform(post("/api/v1/webhooks/gateway")
                        .header("X-Signature", sig)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SETTLED"));
    }

    @Test
    void sameIdempotencyKeyDoesNotLeakAcrossUsers() throws Exception {
        String key = "K-" + UUID.randomUUID();

        mockMvc.perform(post("/api/v1/transfers").with(alice())
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(transferBody(ALICE_ACC, BOB_ACC)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.idempotentReplay").value(false));

        mockMvc.perform(post("/api/v1/transfers").with(bob())
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(transferBody(BOB_ACC, ALICE_ACC)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.idempotentReplay").value(false))
                .andExpect(jsonPath("$.sourceAccountId").value(BOB_ACC));

        mockMvc.perform(post("/api/v1/transfers").with(alice())
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(transferBody(ALICE_ACC, BOB_ACC)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.idempotentReplay").value(true));
    }

    private static Jwt.Builder jwtBuilder() {
        Instant now = Instant.now();
        return Jwt.withTokenValue("t").header("alg", "RS256").subject("u")
                .issuedAt(now).expiresAt(now.plusSeconds(60));
    }

    private static List<String> authorities(Jwt jwt) {
        return new KeycloakRoleConverter().convert(jwt).stream().map(GrantedAuthority::getAuthority).toList();
    }

    @Test
    void keycloakRolesMapOnlyKnownRealmRoles() {
        assertThat(authorities(jwtBuilder().claim("realm_access", Map.of("roles", List.of("CUSTOMER", "offline_access"))).build()))
                .containsExactly("ROLE_CUSTOMER");
        assertThat(authorities(jwtBuilder().claim("realm_access", Map.of("roles", List.of("ADMIN"))).build()))
                .containsExactly("ROLE_ADMIN");
    }

    @Test
    void scopeClaimAndMalformedRealmAccessGrantNothing() {
        assertThat(authorities(jwtBuilder().claim("scope", "ADMIN").build())).isEmpty();
        assertThat(authorities(jwtBuilder().claim("realm_access", "ADMIN").build())).isEmpty();
        assertThat(authorities(jwtBuilder().claim("realm_access", Map.of("roles", "ADMIN")).build())).isEmpty();
        assertThat(authorities(jwtBuilder().claim("realm_access", Map.of("roles", List.of(1, "x"))).build())).isEmpty();
    }

    @Test
    void securityConfigConverterUsesKeycloakRoles() {
        JwtAuthenticationConverter converter = SecurityConfig.jwtAuthenticationConverter();
        Authentication auth = converter.convert(jwtBuilder()
                .claim("realm_access", Map.of("roles", List.of("ADMIN", "offline_access")))
                .claim("scope", "CUSTOMER").build());
        assertThat(auth.getAuthorities()).extracting(GrantedAuthority::getAuthority).containsExactly("ROLE_ADMIN");
    }

    @Test
    void expiredTokenFailsValidation() {
        OAuth2TokenValidator<Jwt> validator = SecurityConfig.tokenValidator(
                "http://localhost:8180/realms/mutabik", "mutabik-api");
        Instant now = Instant.now();
        Jwt expired = Jwt.withTokenValue("t").header("alg", "RS256")
                .issuer("http://localhost:8180/realms/mutabik").audience(List.of("mutabik-api"))
                .issuedAt(now.minusSeconds(7200)).expiresAt(now.minusSeconds(3600)).build();
        assertThat(validator.validate(expired).hasErrors()).isTrue();
    }

    @Test
    void webhookWithInvalidSignatureIsRejectedAndPaymentStaysUnsettled() throws Exception {
        String payBody = objectMapper.writeValueAsString(Map.of(
                "customerAccountId", ALICE_ACC, "merchantAccountId", BOB_ACC,
                "amount", "13.00", "currency", "TRY"));
        MvcResult pay = mockMvc.perform(post("/api/v1/gateway/pay").with(alice())
                        .header("Idempotency-Key", "PSP-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payBody))
                .andExpect(status().isOk())
                .andReturn();
        String ref = (String) objectMapper.readValue(pay.getResponse().getContentAsString(), Map.class)
                .get("externalReferenceId");
        java.math.BigDecimal before = accountRepository.findById(UUID.fromString(ALICE_ACC)).orElseThrow().getBalance();

        String payload = objectMapper.writeValueAsString(Map.of(
                "eventType", "payment_intent.succeeded", "externalReferenceId", ref,
                "amount", "13.00", "currency", "TRY"));
        MvcResult result = mockMvc.perform(post("/api/v1/webhooks/gateway")
                        .header("X-Signature", "deadbeef")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Invalid Webhook Signature"))
                .andReturn();

        assertThat(result.getResponse().getContentAsString()).doesNotContain("SETTLED");
        assertThat(accountRepository.findById(UUID.fromString(ALICE_ACC)).orElseThrow().getBalance())
                .isEqualByComparingTo(before);
    }
}
