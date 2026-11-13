package com.bank.payment.api.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;

import java.util.List;

/**
 * Stateless OAuth2 resource server. Access matrix per the authentication design, section 5.2.
 * Ownership checks (own account or 404) are enforced in the application layer.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http, ObjectMapper objectMapper) throws Exception {
        ProblemAuthenticationEntryPoint entryPoint = new ProblemAuthenticationEntryPoint(objectMapper);
        ProblemAccessDeniedHandler deniedHandler = new ProblemAccessDeniedHandler(objectMapper);

        http
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(a -> a
                        .requestMatchers(HttpMethod.POST, "/api/v1/webhooks/gateway").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/transfers", "/api/v1/gateway/pay").hasRole("CUSTOMER")
                        .requestMatchers("/api/v1/outbox/**").hasRole("ADMIN")
                        .requestMatchers("/api/**").authenticated()
                        .anyRequest().permitAll())
                .exceptionHandling(e -> e
                        .authenticationEntryPoint(entryPoint)
                        .accessDeniedHandler(deniedHandler))
                .oauth2ResourceServer(o -> o
                        .authenticationEntryPoint(entryPoint)
                        .accessDeniedHandler(deniedHandler)
                        .jwt(j -> j.jwtAuthenticationConverter(jwtAuthenticationConverter())));
        return http.build();
    }

    @Bean
    public JwtDecoder jwtDecoder(
            @Value("${mutabik.security.issuer:http://localhost:8180/realms/mutabik}") String issuer,
            @Value("${mutabik.security.jwk-set-uri:${mutabik.security.issuer:http://localhost:8180/realms/mutabik}/protocol/openid-connect/certs}") String jwkSetUri,
            @Value("${mutabik.security.audience:mutabik-api}") String audience) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(jwkSetUri).build();
        decoder.setJwtValidator(tokenValidator(issuer, audience));
        return decoder;
    }

    /** Signature is checked by the decoder; this chain checks issuer, timestamps and audience. */
    public static OAuth2TokenValidator<Jwt> tokenValidator(String issuer, String audience) {
        OAuth2TokenValidator<Jwt> audienceValidator = token -> {
            List<String> aud = token.getAudience();
            if (aud != null && aud.contains(audience)) {
                return OAuth2TokenValidatorResult.success();
            }
            return OAuth2TokenValidatorResult.failure(
                    new OAuth2Error("invalid_token", "Token audience bu API icin gecerli degil", null));
        };
        return new DelegatingOAuth2TokenValidator<>(JwtValidators.createDefaultWithIssuer(issuer), audienceValidator);
    }

    private JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(new KeycloakRoleConverter());
        return converter;
    }
}
