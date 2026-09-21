package com.gr74.booking.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;

import com.gr74.booking.security.KeycloakRealmRoleConverter;
import com.gr74.booking.security.SecurityProblemSupport;

/**
 * Booking as an OAuth2 resource server.
 *
 * <p>The gateway validating the token does <em>not</em> mean Booking can skip it: Payment calls
 * Booking's {@code payability} read server-to-server, and trusting the network would let any
 * process inside the cluster read any booking. So Booking validates every request itself: the JWT
 * signature against Keycloak's public keys (fetched once from the JWKS endpoint and cached), plus
 * expiry and issuer. Validation is local and stateless — no per-request call to Keycloak. Eureka's
 * <em>outbound</em> registration calls are untouched by this chain (it only guards inbound HTTP),
 * so {@code lb://} resolution keeps working.
 *
 * <p><b>The coarse rule lives here, the fine-grained rule on the controllers</b>, which compose: this chain says "authenticated by default, these paths public"; a
 * {@code @PreAuthorize("hasRole('ADMIN')")} on a controller method says "and this one needs ADMIN"
 * (the inventory/showtime writes). The {@code payability} read stays at plain "authenticated" on
 * purpose: Payment relays the <em>user's</em> token there, and it is Payment — which
 * compares the returned {@code userId} against its own request's user — that makes the ownership
 * judgement, not this endpoint.
 *
 * <p><b>Lazy JWKS, eager issuer check.</b> The {@link JwtDecoder} below is built from the JWKS URL
 * derived from the single issuer URI — <em>not</em> via Boot's default issuer-uri discovery, which
 * performs an OIDC metadata fetch <em>eagerly at bean creation</em>. Eager discovery would make
 * this service unbootable whenever Keycloak is briefly unreachable (and would make the hermetic
 * test suite need a live Keycloak). The lazy decoder moves the first network call to the first
 * request — boot never depends on the IdP — while {@code createDefaultWithIssuer} still pins the
 * {@code iss} claim, so a token minted by any other issuer is rejected. One URI feeds both checks,
 * which is exactly the issuer-mismatch trap made impossible: the JWKS source and the
 * accepted {@code iss} cannot drift apart because they are the same string.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    /** The ONE issuer URL (compose comment on {@code KC_HOSTNAME} explains why it must be one). */
    @Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri}")
    private String issuerUri;

    /**
     * The filter-chain failure renderer, as a bean of this config (not a scanned component) so a
     * {@code @WebMvcTest} slice gets the whole chain from a single
     * {@code @Import(SecurityConfig.class)} — see {@link SecurityProblemSupport}. Wired into the
     * chain as a method parameter (not a constructor field) so the config never depends on the
     * bean it defines.
     */
    @Bean
    SecurityProblemSupport securityProblemSupport(ObjectMapper objectMapper) {
        return new SecurityProblemSupport(objectMapper);
    }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http,
            JwtAuthenticationConverter jwtAuthenticationConverter,
            SecurityProblemSupport problems) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(sessions -> sessions.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // Compose probes (and humans) must reach health with no token — gating it
                        // would hang `depends_on: service_healthy` and the whole stack with it.
                        .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                        // The API contract is public: the gateway aggregates these specs into one
                        // Swagger UI that the browser fetches same-origin with no token attached.
                        .requestMatchers("/v3/api-docs/**", "/swagger-ui.html", "/swagger-ui/**")
                        .permitAll()
                        // Everything else — bookings, inventory, showtimes, payability — needs an
                        // authenticated token; the ADMIN-only writes add @PreAuthorize on top.
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter)))
                .exceptionHandling(errors -> errors
                        // Coded ProblemDetail bodies — never an opaque empty 401/403.
                        .authenticationEntryPoint(problems)
                        .accessDeniedHandler(problems));
        return http.build();
    }

    /**
     * Converts the JWT into an {@code Authentication} whose authorities come from Keycloak's
     * {@code realm_access.roles} — <em>not</em> the default {@code scope} mapping (see
     * {@link KeycloakRealmRoleConverter} for why the default silently breaks every role check).
     */
    @Bean
    JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(new KeycloakRealmRoleConverter());
        return converter;
    }

    /**
     * Lazy JWT decoder: fetches Keycloak's public keys on first use and caches them (with rotation
     * handled by the Nimbus {@code RemoteJWKSet} underneath), while always validating expiry and
     * the {@code iss} claim. See the class javadoc for why this is lazy rather than Boot's eager
     * issuer-uri discovery.
     */
    @Bean
    JwtDecoder jwtDecoder() {
        NimbusJwtDecoder decoder =
                NimbusJwtDecoder.withJwkSetUri(issuerUri + "/protocol/openid-connect/certs").build();
        decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(issuerUri));
        return decoder;
    }
}
