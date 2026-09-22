package com.gr74.gateway.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import jakarta.servlet.DispatcherType;

import org.springframework.http.HttpMethod;
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
 * The edge as an OAuth2 resource server. Validates the JWT before routing; services validate again.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    /** The issuer URL. */
    @Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri}")
    private String issuerUri;

    /** Filter-chain failure renderer, exposed as a bean for test slices. */
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
                        // Error forwards re-dispatch through the chain — let them through.
                        .dispatcherTypeMatchers(DispatcherType.ERROR, DispatcherType.FORWARD)
                        .permitAll()
                        // Health stays public.
                        .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                        // Aggregated Swagger UI fetches specs same-origin with no token.
                        .requestMatchers("/swagger-ui.html", "/swagger-ui/**", "/v3/api-docs/**",
                                "/catalog/v3/api-docs/**", "/booking/v3/api-docs/**",
                                "/payment/v3/api-docs/**")
                        .permitAll()
                        // Payment webhooks arrive with no JWT (HMAC-verified in Payment). Match on the
                        // incoming /api path — StripPrefix runs later during routing.
                        .requestMatchers(HttpMethod.POST, "/api/payments/webhooks/**").permitAll()
                        // Everything else needs a valid token; fine-grained roles live on services.
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter)))
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint(problems)
                        .accessDeniedHandler(problems));
        return http.build();
    }

    /** Converts the JWT into an {@code Authentication} from Keycloak realm roles. */
    @Bean
    JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(new KeycloakRealmRoleConverter());
        return converter;
    }

    /** Lazy JWT decoder backed by Keycloak's JWKS. */
    @Bean
    JwtDecoder jwtDecoder() {
        NimbusJwtDecoder decoder =
                NimbusJwtDecoder.withJwkSetUri(issuerUri + "/protocol/openid-connect/certs").build();
        decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(issuerUri));
        return decoder;
    }
}
