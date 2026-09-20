package com.gr74.gateway.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;

import com.gr74.gateway.security.KeycloakRealmRoleConverter;
import com.gr74.gateway.security.SecurityProblemSupport;

/**
 * The edge as an OAuth2 resource server.
 *
 * <p>The gateway validates the user's Bearer JWT <em>first</em> — signature against Keycloak's
 * public keys (fetched once from the JWKS endpoint and cached), plus expiry and issuer — rejecting
 * garbage before it touches the cluster. This is the fail-fast half of zero trust, not a
 * replacement for it: every backend service validates <em>again</em> (a misconfigured route, or a
 * caller already inside the network, must not mean open services).
 *
 * <p><b>No TokenRelay filter is configured — on purpose.</b> The gateway proxies the request,
 * headers included, so the incoming {@code Authorization: Bearer …} header reaches the downstream
 * service byte-identical without any filter: that propagation <em>is</em> the relay at the edge.
 * (The {@code TokenRelay} filter belongs to the BFF/login flow where the gateway <em>holds</em>
 * the token; here the client holds it and the gateway just forwards it.)
 *
 * <p><b>The gateway strips {@code X-User-Id}</b> ({@code RemoveRequestHeader} on every API route
 * in {@code application.yml}). The header was the previous identity mechanism; any service
 * that stopped reading it must never receive a stale one, and no downstream code may ever treat a
 * client-set header as identity again.
 *
 * <p><b>Lazy JWKS, eager issuer check.</b> The {@link JwtDecoder} below is built from the JWKS URL
 * derived from the single issuer URI — <em>not</em> via Boot's default issuer-uri discovery, which
 * performs an OIDC metadata fetch <em>eagerly at bean creation</em>. Eager discovery would make
 * the gateway unbootable whenever Keycloak is briefly unreachable (and would make the hermetic
 * test suite need a live Keycloak). The lazy decoder moves the first network call to the first
 * request — boot never depends on the IdP — while {@code createDefaultWithIssuer} still pins the
 * {@code iss} claim, so a token minted by any other issuer is rejected. One URI feeds both checks,
 * which is exactly the issuer-mismatch trap made impossible: the JWKS source and the
 * accepted {@code iss} cannot drift apart because they are the same string.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    /** The ONE issuer URL (compose comment on {@code KC_HOSTNAME} explains why it must be one). */
    @Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri}")
    private String issuerUri;

    /**
     * The filter-chain failure renderer, as a bean of this config (not a scanned component) so a
     * test slice gets the whole chain from a single {@code @Import(SecurityConfig.class)} — see
     * {@link SecurityProblemSupport}. Wired into the chain as a method parameter (not a
     * constructor field) so the config never depends on the bean it defines.
     */
    @Bean
    SecurityProblemSupport securityProblemSupport(com.fasterxml.jackson.databind.ObjectMapper objectMapper) {
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
                        // Health stays public — a gated health endpoint hangs orchestration probes.
                        .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                        // The aggregated Swagger UI: the browser fetches the specs same-origin with
                        // no token attached, so docs (the contract, not the data) stay public.
                        .requestMatchers("/swagger-ui.html", "/swagger-ui/**", "/v3/api-docs/**",
                                "/catalog/v3/api-docs/**", "/booking/v3/api-docs/**",
                                "/payment/v3/api-docs/**")
                        .permitAll()
                        // Coarse edge split: everything else needs a valid token.
                        // Fine-grained roles live on the services' controllers.
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
