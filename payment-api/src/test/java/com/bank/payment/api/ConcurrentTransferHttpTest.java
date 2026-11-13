package com.bank.payment.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** Regression: concurrent transfers on the same source must serialize on the row lock, none lost. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
@Import(SecurityIntegrationTest.TestKafkaConfig.class)
class ConcurrentTransferHttpTest {

    private static final String ALICE_ACC = "11111111-1111-1111-1111-111111111111";
    private static final String BOB_ACC = "22222222-2222-2222-2222-222222222222";
    private static final int N = 20;

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;

    private static RequestPostProcessor alice() {
        return jwt().jwt(j -> j.claim("customer_id", "CUST-ALICE"))
                .authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER"));
    }

    private BigDecimal aliceBalance() throws Exception {
        String body = mockMvc.perform(get("/api/v1/accounts/" + ALICE_ACC).with(alice()))
                .andReturn().getResponse().getContentAsString();
        return new BigDecimal(objectMapper.readTree(body).get("balance").asText());
    }

    @Test
    void twentyConcurrentTransfersFromSameSourceAllSucceedAndDebitExactly() throws Exception {
        BigDecimal before = aliceBalance();
        String body = objectMapper.writeValueAsString(Map.of(
                "sourceAccountId", ALICE_ACC, "targetAccountId", BOB_ACC,
                "amount", "1.00", "currency", "TRY", "description", "concurrent"));

        ExecutorService pool = Executors.newFixedThreadPool(N);
        CountDownLatch ready = new CountDownLatch(N);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();
        for (int i = 0; i < N; i++) {
            results.add(pool.submit(() -> {
                ready.countDown();
                go.await();
                return mockMvc.perform(post("/api/v1/transfers").with(alice())
                                .header("Idempotency-Key", "CONC-" + UUID.randomUUID())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body))
                        .andReturn().getResponse().getStatus();
            }));
        }
        assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
        go.countDown();
        List<Integer> statuses = new ArrayList<>();
        for (Future<Integer> f : results) {
            statuses.add(f.get(60, TimeUnit.SECONDS));
        }
        pool.shutdownNow();

        assertThat(statuses).as("HTTP statuses").containsOnly(200);
        assertThat(before.subtract(aliceBalance())).isEqualByComparingTo("20.00");
    }
}
