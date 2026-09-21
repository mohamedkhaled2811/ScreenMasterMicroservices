package com.gr74.notification.security;

import java.util.Collection;
import java.util.List;
import java.util.Map;

import org.springframework.core.convert.converter.Converter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

import lombok.extern.slf4j.Slf4j;

/**
 * Maps Keycloak's realm roles to Spring Security authorities.
 *
 * <p><b>Why this class exists at all:</b> Spring Security's default
 * {@code JwtAuthenticationConverter} reads the {@code scope} (or {@code scp}) claim and turns each
 * scope into a {@code SCOPE_…} authority. Keycloak does put the client's scopes there — but the
 * roles this system authorizes on ({@code USER}, {@code ADMIN}) live somewhere else entirely: the
 * {@code realm_access.roles} claim inside the JWT payload, e.g.
 * {@code "realm_access": {"roles": ["USER"]}}. With the default converter every
 * {@code hasRole('ADMIN')} silently evaluates to {@code false}: no exception, no log line — the
 * single most common Keycloak + Spring misconfiguration, and the reason this converter gets its own
 * unit test ({@code KeycloakRealmRoleConverterTest}) proving a token with
 * {@code realm_access.roles=["ADMIN"]} actually yields {@code ROLE_ADMIN}. Notification has no
 * role-gated endpoint <em>today</em>, but the converter is wired from day one so the first
 * {@code @PreAuthorize} added here works instead of silently denying.
 *
 * <p><b>Role naming:</b> Keycloak realm roles are used verbatim ({@code USER}, {@code ADMIN}) and
 * prefixed with {@code ROLE_} here, because Spring's {@code hasRole('ADMIN')} matches against the
 * authority {@code ROLE_ADMIN}. A role that already carries the prefix is left alone (defensive —
 * Keycloak lets an operator name a role anything, including {@code ROLE_X}).
 *
 * <p>Each service owns an identical copy of this class on purpose: the repo draws a hard
 * no-shared-jar boundary between services (a shared security jar would couple every service's
 * deploy to every other's — the same reason the outbox relay was copied, not extracted). The class
 * is stateless and dependency-free, so the duplication costs nothing at runtime.
 */
@Slf4j
public class KeycloakRealmRoleConverter implements Converter<Jwt, Collection<GrantedAuthority>> {

    /** The JWT claim Keycloak writes the realm roles into. Not {@code scope} — see above. */
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
