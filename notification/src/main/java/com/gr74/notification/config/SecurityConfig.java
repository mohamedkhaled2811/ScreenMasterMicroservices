package com.gr74.notification.config;

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

import com.gr74.notification.security.KeycloakRealmRoleConverter;
import com.gr74.notification.security.SecurityProblemSupport;

/**
 * Notification as an OAuth2 resource server (<b>zero trust</b>).
 *
 * <p>The odd one out, deliberately: Notification's business path is the <em>broker</em> (it
 * consumes {@code BookingConfirmed} off RabbitMQ), and queue messages carry <em>no</em> token by
 * design — a user JWT would be expired by consume time and would be a credential at rest in a
 * durable log. So today this chain guards only the operational surface (actuator + docs + the
 * default-deny on anything else); there are no business controllers and no {@code @PreAuthorize}
 * yet. It exists so the invariant "every service validates the token" has no exception to narrate:
 * the day this service grows an HTTP endpoint, the fence is already up. The machine-token half of
 * Notification's auth (how it acts <em>as itself</em> toward Keycloak) lives separately in
 * {@code MachineTokenProvider} — inbound validation here, outbound identity there.
 *
 * <p>Eureka's <em>outbound</em> registration calls are untouched by this chain (it only guards
 * inbound HTTP), so {@code lb://} resolution keeps working.
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
                        // Compose probes (and humans) must reach health with no token — gating it
                        // would hang `depends_on: service_healthy` and the whole stack with it.
                        .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                        // The API contract is public: the gateway aggregates these specs into one
                        // Swagger UI that the browser fetches same-origin with no token attached.
                        .requestMatchers("/v3/api-docs/**", "/swagger-ui.html", "/swagger-ui/**")
                        .permitAll()
                        // No business controllers exist — default-deny everything else so the first
                        // endpoint added here is fenced from birth.
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
     * Wired from day one so the first {@code @PreAuthorize} added here works instead of
     * silently denying.
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
