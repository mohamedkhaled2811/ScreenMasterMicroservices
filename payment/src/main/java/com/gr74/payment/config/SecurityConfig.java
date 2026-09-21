package com.gr74.payment.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;

import com.gr74.payment.security.KeycloakRealmRoleConverter;
import com.gr74.payment.security.SecurityProblemSupport;

/**
 * Payment as an OAuth2 resource server (<b>zero trust</b>).
 *
 * <p>The gateway validating the token does <em>not</em> mean Payment can skip it: Payment moves
 * money, so a token that is merely forwarded-but-never-checked would let any process inside the
 * cluster open checkouts. Every request is validated here — signature against Keycloak's public
 * keys (fetched once from JWKS and cached), plus expiry and issuer — locally and statelessly.
 * Eureka's <em>outbound</em> registration calls are untouched by this chain (it only guards inbound
 * HTTP), so {@code lb://} resolution keeps working.
 *
 * <p><b>The coarse rule lives here, the fine-grained rule on the controllers</b> (which compose): this chain says "authenticated by default, these paths public"; a
 * {@code @PreAuthorize("hasRole('ADMIN')")} on {@code RefundController} says "refunds need ADMIN".
 * Two paths are public <em>by decision</em>, and both come with a paragraph explaining why
 * {@code permitAll} is not a hole:
 * <ul>
 *   <li>{@code /payments/webhooks/**} — gateways hold no JWT and never will; these deliveries are
 *       authenticated by <b>HMAC signature verification</b> over the raw body bytes,
 *       which proves the sender just as well. {@code permitAll} at the security layer plus a
 *       signature check in the controller is the deliberate shape — and the forged-signature test
 *       pins that a bad signature still yields {@code 400 PAYMENT_WEBHOOK_SIGNATURE_INVALID}, not a
 *       free pass.</li>
 *   <li>{@code /payments/sandbox/checkout/**} — the sandbox's hosted pay page. It <em>simulates a
 *       third party</em> (Stripe's hosted form): a real gateway page is public by nature (its long
 *       session id is the capability, exactly like a real checkout URL), so demanding <em>our</em>
 *       JWT there would model the wrong trust relationship.</li>
 * </ul>
 *
 * <p>The resilience actuator endpoints ({@code circuitbreakers}, {@code bulkheads}, …)
 * move behind {@code ADMIN} here: they expose gateway internals (failure counts, state) that help
 * an attacker time abuse.
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
 *
 * <p><b>Raw-byte warning:</b> this chain must never consume the webhook request body — signature
 * verification reads the exact bytes in the controller ({@code @RequestBody byte[]}). The chain
 * above adds no body-reading filter, and {@code WebhookControllerTest} pins the byte-for-byte
 * pass-through <em>with the real chain in place</em>.
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
                        // Compose probes (and humans) must reach health with no token — gating it
                        // would hang `depends_on: service_healthy` and the whole stack with it.
                        .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                        // The API contract is public: the gateway aggregates these specs into one
                        // Swagger UI that the browser fetches same-origin with no token attached.
                        .requestMatchers("/v3/api-docs/**", "/swagger-ui.html", "/swagger-ui/**")
                        .permitAll()
                        // Gateways have no JWT: signature-authenticated, not token-authenticated.
                        .requestMatchers("/payments/webhooks/**").permitAll()
                        // The fake third party's hosted page (public like a real checkout URL).
                        .requestMatchers("/payments/sandbox/checkout/**").permitAll()
                        // Resilience internals: failure counts and breaker state help an attacker
                        // time abuse — admins only.
                        .requestMatchers("/actuator/circuitbreakers", "/actuator/circuitbreakers/**",
                                "/actuator/circuitbreakerevents", "/actuator/circuitbreakerevents/**",
                                "/actuator/bulkheads", "/actuator/bulkheads/**",
                                "/actuator/retries", "/actuator/retries/**",
                                "/actuator/metrics", "/actuator/metrics/**")
                        .hasRole("ADMIN")
                        // Session creation, payment reads: any authenticated token (ownership is
                        // checked in the service against the JWT `sub`, not by role).
                        .requestMatchers(HttpMethod.GET, "/payments/gateways", "/payments/gateways/**")
                        .authenticated()
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
