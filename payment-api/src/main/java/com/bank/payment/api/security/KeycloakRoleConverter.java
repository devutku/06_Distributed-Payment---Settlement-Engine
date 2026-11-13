package com.bank.payment.api.security;

import org.springframework.core.convert.converter.Converter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Maps Keycloak {@code realm_access.roles} to Spring authorities. Only the two roles the API knows
 * (CUSTOMER, ADMIN) are mapped; Keycloak built-ins such as offline_access are ignored.
 */
public class KeycloakRoleConverter implements Converter<Jwt, Collection<GrantedAuthority>> {

    private static final Set<String> KNOWN_ROLES = Set.of("CUSTOMER", "ADMIN");

    @Override
    public Collection<GrantedAuthority> convert(Jwt jwt) {
        Object realmAccess = jwt.getClaim("realm_access");
        if (!(realmAccess instanceof Map<?, ?> map) || !(map.get("roles") instanceof Collection<?> roles)) {
            return List.of();
        }
        return roles.stream()
                .filter(String.class::isInstance)
                .map(String.class::cast)
                .filter(KNOWN_ROLES::contains)
                .<GrantedAuthority>map(r -> new SimpleGrantedAuthority("ROLE_" + r))
                .toList();
    }
}
