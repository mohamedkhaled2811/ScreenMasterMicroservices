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
 * OAuth2 resource-server chain: JWT required by default, webhooks and docs public.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    /** Single issuer URL for both JWKS lookup and {@code iss} validation. */
    @Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri}")
    private String issuerUri;

    /** Filter-chain failure renderer, exposed as a bean for {@code @WebMvcTest} slices. */
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
                        // Health stays public so container probes never need a token.
                        .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                        // API docs stay public for the aggregated Swagger UI.
                        .requestMatchers("/v3/api-docs/**", "/swagger-ui.html", "/swagger-ui/**")
                        .permitAll()
                        // Webhooks carry HMAC signatures instead of JWTs.
                        .requestMatchers("/payments/webhooks/**").permitAll()
                        // Sandbox pay page is public like a real hosted checkout URL.
                        .requestMatchers("/payments/sandbox/checkout/**").permitAll()
                        // Resilience internals are admin-only.
                        .requestMatchers("/actuator/circuitbreakers", "/actuator/circuitbreakers/**",
                                "/actuator/circuitbreakerevents", "/actuator/circuitbreakerevents/**",
                                "/actuator/bulkheads", "/actuator/bulkheads/**",
                                "/actuator/retries", "/actuator/retries/**",
                                "/actuator/metrics", "/actuator/metrics/**")
                        .hasRole("ADMIN")
                        // Payment reads need any authenticated token; ownership is checked in the service.
                        .requestMatchers(HttpMethod.GET, "/payments/gateways", "/payments/gateways/**")
                        .authenticated()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter)))
                .exceptionHandling(errors -> errors
                        // Coded ProblemDetail bodies for 401/403.
                        .authenticationEntryPoint(problems)
                        .accessDeniedHandler(problems));
        return http.build();
    }

    /** Maps Keycloak realm roles to Spring authorities. */
    @Bean
    JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(new KeycloakRealmRoleConverter());
        return converter;
    }

    /** Lazy JWKS decoder that also validates expiry and issuer. */
    @Bean
    JwtDecoder jwtDecoder() {
        NimbusJwtDecoder decoder =
                NimbusJwtDecoder.withJwkSetUri(issuerUri + "/protocol/openid-connect/certs").build();
        decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(issuerUri));
        return decoder;
    }
}
