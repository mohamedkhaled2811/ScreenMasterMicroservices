package com.gr74.notification.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

/** Maps Keycloak realm_access roles to Spring ROLE_ authorities. */
class KeycloakRealmRoleConverterTest {

    private final KeycloakRealmRoleConverter converter = new KeycloakRealmRoleConverter();

    @Test
    @DisplayName("realm_access.roles=[ADMIN] yields ROLE_ADMIN")
    void adminRoleMapsToRoleAdmin() {
        Collection<GrantedAuthority> authorities = converter.convert(jwtWithRoles("ADMIN"));

        assertThat(authorities).extracting(GrantedAuthority::getAuthority)
                .containsExactly("ROLE_ADMIN");
    }

    @Test
    @DisplayName("multiple realm roles each gain the ROLE_ prefix")
    void multipleRolesEachGainPrefix() {
        Collection<GrantedAuthority> authorities = converter.convert(jwtWithRoles("USER", "ADMIN"));

        assertThat(authorities).extracting(GrantedAuthority::getAuthority)
                .containsExactlyInAnyOrder("ROLE_USER", "ROLE_ADMIN");
    }

    @Test
    @DisplayName("a token with no realm_access claim maps to no authorities (not null, not a 500)")
    void missingRealmAccessMapsToEmpty() {
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "none")
                .subject("user-1")
                .issuedAt(Instant.now().minusSeconds(60))
                .expiresAt(Instant.now().plusSeconds(300))
                .build();

        assertThat(converter.convert(jwt)).isEmpty();
    }

    @Test
    @DisplayName("non-string entries in the roles list are ignored, not exploded on")
    void nonStringRolesIgnored() {
        Map<String, Object> realmAccess = new java.util.HashMap<>();
        realmAccess.put("roles", java.util.Arrays.asList("USER", 42, null));
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "none")
                .subject("user-1")
                .claim("realm_access", realmAccess)
                .issuedAt(Instant.now().minusSeconds(60))
                .expiresAt(Instant.now().plusSeconds(300))
                .build();

        assertThat(converter.convert(jwt)).extracting(GrantedAuthority::getAuthority)
                .containsExactly("ROLE_USER");
    }

    private static Jwt jwtWithRoles(String... roles) {
        return Jwt.withTokenValue("token")
                .header("alg", "none")
                .subject("user-1")
                .claim("realm_access", Map.of("roles", List.of(roles)))
                .issuedAt(Instant.now().minusSeconds(60))
                .expiresAt(Instant.now().plusSeconds(300))
                .build();
    }
}
