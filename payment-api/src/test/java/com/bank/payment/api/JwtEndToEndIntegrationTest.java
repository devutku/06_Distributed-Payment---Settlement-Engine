package com.bank.payment.api;

import com.bank.payment.api.security.SecurityConfig;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.support.SendResult;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Runs bearer tokens through the REAL decoder (signature + production validator chain) and the real
 * jwtAuthenticationConverter; only the key source differs from production (a local RSA key instead of the JWKS URI).
 * The decoder override lives in this test configuration only.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
@Import(JwtEndToEndIntegrationTest.RealJwtConfig.class)
class JwtEndToEndIntegrationTest {

    private static final String ISSUER = "http://localhost:8180/realms/mutabik";
    private static final String AUDIENCE = "mutabik-api";
    private static final String ALICE_ACC = "11111111-1111-1111-1111-111111111111";

    private static final KeyPair KEY_PAIR = generateKeyPair();

    private static KeyPair generateKeyPair() {
        try {
            KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
            g.initialize(2048);
            return g.generateKeyPair();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @TestConfiguration
    static class RealJwtConfig {
        @Bean
        @Primary
        public JwtDecoder testJwtDecoder() {
            NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey((RSAPublicKey) KEY_PAIR.getPublic()).build();
            decoder.setJwtValidator(SecurityConfig.tokenValidator(ISSUER, AUDIENCE));
            return decoder;
        }

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

    private static String sign(JWTClaimsSet.Builder claims) throws Exception {
        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).type(JOSEObjectType.JWT).build(),
                claims.build());
        jwt.sign(new RSASSASigner((RSAPrivateKey) KEY_PAIR.getPrivate()));
        return jwt.serialize();
    }

    private static JWTClaimsSet.Builder baseClaims() {
        Instant now = Instant.now();
        return new JWTClaimsSet.Builder()
                .subject("user-1")
                .issuer(ISSUER)
                .audience(AUDIENCE)
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plusSeconds(300)));
    }

    @Test
    void customerTokenThroughRealDecoderSeesOnlyOwnAccount() throws Exception {
        String token = sign(baseClaims()
                .claim("realm_access", Map.of("roles", List.of("CUSTOMER")))
                .claim("customer_id", "CUST-ALICE"));

        mockMvc.perform(get("/api/v1/accounts").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].accountId").value(ALICE_ACC));
    }

    @Test
    void scopeAdminWithoutRealmRolesIsNotAdmin() throws Exception {
        String token = sign(baseClaims().claim("scope", "ADMIN"));

        int status = mockMvc.perform(get("/api/v1/outbox").header("Authorization", "Bearer " + token))
                .andReturn().getResponse().getStatus();
        org.assertj.core.api.Assertions.assertThat(status).isIn(401, 403);
    }

    @Test
    void adminRealmRoleReachesOutbox() throws Exception {
        String token = sign(baseClaims().claim("realm_access", Map.of("roles", List.of("ADMIN"))));

        mockMvc.perform(get("/api/v1/outbox").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
    }

    @Test
    void tokenWithWrongAudienceIsRejected() throws Exception {
        String token = sign(baseClaims().audience("other-api")
                .claim("realm_access", Map.of("roles", List.of("CUSTOMER")))
                .claim("customer_id", "CUST-ALICE"));

        mockMvc.perform(get("/api/v1/accounts").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
    }
}
