package com.bank.payment.api.security;

import com.bank.payment.application.security.Requester;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.stereotype.Component;

/**
 * The only place an HTTP identity is turned into an application-layer {@link Requester}.
 * Never produces {@code Requester.system()}.
 */
@Component
public class RequesterResolver {

    public static final String CUSTOMER_ID_CLAIM = "customer_id";

    public Requester resolve(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) {
            throw new InvalidBearerTokenException("Kimlik dogrulanamadi");
        }
        if (hasRole(authentication, "ROLE_ADMIN")) {
            return Requester.admin();
        }
        if (hasRole(authentication, "ROLE_CUSTOMER")
                && authentication.getPrincipal() instanceof Jwt jwt) {
            String customerId = jwt.getClaimAsString(CUSTOMER_ID_CLAIM);
            if (customerId != null && !customerId.isBlank()) {
                return Requester.customer(customerId);
            }
        }
        throw new InvalidBearerTokenException("Token gecerli bir musteri veya yonetici kimligi icermiyor");
    }

    private static boolean hasRole(Authentication authentication, String role) {
        return authentication.getAuthorities().stream().anyMatch(a -> role.equals(a.getAuthority()));
    }
}
