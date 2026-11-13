package com.bank.payment.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** HTTP-level proof of the ownership claims: 404 for not-owned == 404 for non-existent, no leaks. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
@Import(SecurityIntegrationTest.TestKafkaConfig.class)
class AccessControlHttpTest {

    private static final String ALICE_ACC = "11111111-1111-1111-1111-111111111111";
    private static final String BOB_ACC = "22222222-2222-2222-2222-222222222222";
    private static final String CHARLIE_ACC = "33333333-3333-3333-3333-333333333333";
    private static final String CLEARING_ACC = "99999999-9999-9999-9999-999999999999";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;

    private static RequestPostProcessor customer(String customerId) {
        return jwt().jwt(j -> j.claim("customer_id", customerId))
                .authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER"));
    }

    private static RequestPostProcessor alice() { return customer("CUST-ALICE"); }

    private static RequestPostProcessor bob() { return customer("CUST-BOB"); }

    private String transferBody(String source, String target) throws Exception {
        return objectMapper.writeValueAsString(Map.of(
                "sourceAccountId", source, "targetAccountId", target,
                "amount", "1.00", "currency", "TRY", "description", "sec test"));
    }

    private MockHttpServletRequestBuilder transferFrom(String source, String target) throws Exception {
        return post("/api/v1/transfers").with(alice())
                .header("Idempotency-Key", "K-" + UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON)
                .content(transferBody(source, target));
    }

    private MockHttpServletRequestBuilder payFrom(String customerAccount, String merchantAccount) throws Exception {
        return post("/api/v1/gateway/pay").with(alice())
                .header("Idempotency-Key", "K-" + UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                        "customerAccountId", customerAccount, "merchantAccountId", merchantAccount, "amount", "1.00")));
    }

    interface AccountCall {
        MockHttpServletRequestBuilder build(AccessControlHttpTest t, String accountId) throws Exception;
    }

    static Stream<Arguments> accountTakingEndpoints() {
        return Stream.of(
                Arguments.of("POST /transfers source", (AccountCall) (t, id) -> t.transferFrom(id, CHARLIE_ACC)),
                Arguments.of("POST /gateway/pay customerAccountId", (AccountCall) (t, id) -> t.payFrom(id, CHARLIE_ACC)),
                Arguments.of("GET /accounts/{id}", (AccountCall) (t, id) -> get("/api/v1/accounts/" + id).with(alice())),
                Arguments.of("GET /accounts/{id}/statement",
                        (AccountCall) (t, id) -> get("/api/v1/accounts/" + id + "/statement").with(alice())));
    }

    /** Status plus body with the volatile timestamp and the echoed request path removed. */
    @SuppressWarnings("unchecked")
    private Map<String, Object> observe(MockHttpServletRequestBuilder request) throws Exception {
        MvcResult r = mockMvc.perform(request).andReturn();
        Map<String, Object> body = new HashMap<>(objectMapper.readValue(r.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8), Map.class));
        body.remove("timestamp");
        body.remove("instance"); // echoes the caller's own request path, not server knowledge
        body.put("_status", r.getResponse().getStatus());
        return body;
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("accountTakingEndpoints")
    void notOwnedAndNonExistentAccountsAreIndistinguishable(String name, AccountCall call) throws Exception {
        Map<String, Object> notOwned = observe(call.build(this, BOB_ACC));
        Map<String, Object> missing = observe(call.build(this, UUID.randomUUID().toString()));

        assertThat(notOwned.get("_status")).isEqualTo(404);
        assertThat(notOwned).isEqualTo(missing);
        assertThat(notOwned.toString()).doesNotContain(BOB_ACC);
        assertThat(notOwned.get("detail")).isEqualTo("Hesap bulunamadı");
    }

    @Test
    void notOwnedSourceWithMissingCounterpartyMatchesMissingSource() throws Exception {
        String ghost = UUID.randomUUID().toString();
        String otherGhost = UUID.randomUUID().toString();

        Map<String, Object> transferNotOwned = observe(transferFrom(BOB_ACC, ghost));
        assertThat(transferNotOwned.get("_status")).isEqualTo(404);
        assertThat(transferNotOwned).isEqualTo(observe(transferFrom(otherGhost, ghost)));

        Map<String, Object> payNotOwned = observe(payFrom(BOB_ACC, ghost));
        assertThat(payNotOwned.get("_status")).isEqualTo(404);
        assertThat(payNotOwned).isEqualTo(observe(payFrom(otherGhost, ghost)));
    }

    @Test
    void alicePayingFromBobsAccountIs404() throws Exception {
        mockMvc.perform(payFrom(BOB_ACC, CHARLIE_ACC))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Account Not Found"));
    }

    @Test
    void aliceCannotReadBobsAccount() throws Exception {
        mockMvc.perform(get("/api/v1/accounts/" + BOB_ACC).with(alice()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Account Not Found"));
    }

    @Test
    void customerTransferListContainsOnlyTransfersInvolvingHerAccounts() throws Exception {
        MvcResult bobToCharlie = mockMvc.perform(post("/api/v1/transfers").with(bob())
                        .header("Idempotency-Key", "K-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(transferBody(BOB_ACC, CHARLIE_ACC)))
                .andExpect(status().isOk())
                .andReturn();
        String bcId = (String) objectMapper.readValue(bobToCharlie.getResponse().getContentAsString(), Map.class)
                .get("transferId");

        // one that does involve Alice, so the list is not trivially empty
        mockMvc.perform(transferFrom(ALICE_ACC, BOB_ACC)).andExpect(status().isOk());

        MvcResult aliceList = mockMvc.perform(get("/api/v1/transfers").with(alice()))
                .andExpect(status().isOk())
                .andReturn();
        List<?> rows = objectMapper.readValue(aliceList.getResponse().getContentAsString(), List.class);
        assertThat(rows).isNotEmpty();
        for (Object row : rows) {
            Map<?, ?> m = (Map<?, ?>) row;
            assertThat(m.get("transferId")).isNotEqualTo(bcId);
            assertThat(ALICE_ACC.equals(m.get("sourceAccountId")) || ALICE_ACC.equals(m.get("targetAccountId")))
                    .as("transfer %s involves Alice", m.get("transferId")).isTrue();
        }
    }

    @Test
    void recipientsForAliceAreMaskedBobAndCharlieOnly() throws Exception {
        MvcResult res = mockMvc.perform(get("/api/v1/accounts/recipients").with(alice()))
                .andExpect(status().isOk())
                .andReturn();
        List<?> rows = objectMapper.readValue(res.getResponse().getContentAsString(), List.class);

        assertThat(rows.stream().map(r -> (Object) ((Map<?, ?>) r).get("accountId")).toList())
                .containsExactlyInAnyOrder(BOB_ACC, CHARLIE_ACC);
        for (Object row : rows) {
            Map<?, ?> m = (Map<?, ?>) row;
            assertThat(m.keySet().stream().map(String::valueOf).toList()).containsExactlyInAnyOrder("accountId", "displayName", "maskedIban");
            assertThat(m.get("maskedIban").toString()).contains("****").doesNotContain("123456780");
        }
        assertThat(res.getResponse().getContentAsString())
                .doesNotContain("alance").doesNotContain(CLEARING_ACC).doesNotContain(ALICE_ACC);
    }

    @Test
    void malformedJsonIs400() throws Exception {
        mockMvc.perform(post("/api/v1/transfers").with(alice())
                        .header("Idempotency-Key", "K-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{not json"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
    }

    @Test
    void badPathVariableIs400() throws Exception {
        mockMvc.perform(get("/api/v1/accounts/" + "a".repeat(36)).with(alice()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void overLongIdempotencyKeyIs400OnTransfersAndGatewayPay() throws Exception {
        String longKey = "K".repeat(101);
        mockMvc.perform(post("/api/v1/transfers").with(alice())
                        .header("Idempotency-Key", longKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(transferBody(ALICE_ACC, BOB_ACC)))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));

        mockMvc.perform(post("/api/v1/gateway/pay").with(alice())
                        .header("Idempotency-Key", longKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "customerAccountId", ALICE_ACC, "merchantAccountId", BOB_ACC, "amount", "1.00"))))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));

        // exactly 100 characters is still accepted
        mockMvc.perform(post("/api/v1/transfers").with(alice())
                        .header("Idempotency-Key", "K".repeat(64) + UUID.randomUUID().toString().substring(0, 36))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(transferBody(ALICE_ACC, BOB_ACC)))
                .andExpect(status().isOk());
    }
}
