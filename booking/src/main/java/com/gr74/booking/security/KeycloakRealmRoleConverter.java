package com.gr74.booking.security;

import java.util.Collection;
import java.util.List;
import java.util.Map;

import org.springframework.core.convert.converter.Converter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

import lombok.extern.slf4j.Slf4j;

/**
 * Maps Keycloak's {@code realm_access.roles} to Spring authorities with the {@code ROLE_} prefix.
 */
@Slf4j
public class KeycloakRealmRoleConverter implements Converter<Jwt, Collection<GrantedAuthority>> {

    /** JWT claim holding the realm roles (not {@code scope}). */
    private static final String REALM_ACCESS_CLAIM = "realm_access";

    /** The nested key holding the role names inside {@code realm_access}. */
    private static final String ROLES_KEY = "roles";

    /** The prefix Spring's {@code hasRole} expects on the authority. */
    private static final String ROLE_PREFIX = "ROLE_";

    @Override
    @SuppressWarnings("unchecked")
    public Collection<GrantedAuthority> convert(Jwt jwt) {
        Object realmAccess = jwt.getClaims().get(REALM_ACCESS_CLAIM);
        if (!(realmAccess instanceof Map<?, ?> accessMap)) {
            return List.of();
        }
        Object roles = accessMap.get(ROLES_KEY);
        if (!(roles instanceof List<?> roleList)) {
            return List.of();
        }
        List<GrantedAuthority> authorities = roleList.stream()
                .filter(String.class::isInstance)
                .map(String.class::cast)
                .map(role -> role.startsWith(ROLE_PREFIX) ? role : ROLE_PREFIX + role)
                .map(SimpleGrantedAuthority::new)
                .map(GrantedAuthority.class::cast)
                .toList();
        log.debug("Mapped {} realm role(s) from JWT sub={}", authorities.size(), jwt.getSubject());
        return authorities;
    }
}
