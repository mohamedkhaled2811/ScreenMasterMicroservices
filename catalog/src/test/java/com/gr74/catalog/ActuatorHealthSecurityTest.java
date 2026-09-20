package com.gr74.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * The two unauthenticated entry points, proven over real HTTP against the full application (real
 * filter chain, real actuator):
 * <ul>
 *   <li>{@code GET /actuator/health} answers 200 with NO token. Compose gates service startup on
 *       this endpoint; if it ever started 401-ing, {@code depends_on: service_healthy} would hang
 *       the whole stack in a failure that looks completely unrelated to auth.</li>
 *   <li>{@code GET /movies} (the public catalogue read) answers 200 with NO token —
 *       browse-before-signup must work anonymously.</li>
 * </ul>
 *
 * <p>Hermetic by construction: no token means the lazy {@code JwtDecoder} is never invoked, so no
 * Keycloak is needed (same {@code src/test/resources/application.yml} as the smoke test — H2,
 * Eureka off).
 *
 * <p>Boot-4 note: {@code TestRestTemplate} moved to {@code spring-boot-resttestclient} and its bean
 * is no longer automatic — {@code @AutoConfigureTestRestTemplate} declares it.
 */
@AutoConfigureTestRestTemplate
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ActuatorHealthSecurityTest {

    @Autowired
    private TestRestTemplate rest;

    @Test
    @DisplayName("GET /actuator/health is reachable with no token (compose healthchecks depend on it)")
    void healthIsPublic() {
        ResponseEntity<String> response = rest.getForEntity("/actuator/health", String.class);

        // The SECURITY assertion is "not 401/403" — the endpoint answered instead of rejecting.
        // The exact 2xx/5xx depends on backing systems, not auth: hermetically (no broker here)
        // downstream indicators report DOWN and health is 503; in Compose with everything up it is
        // 200 UP. Either way the gate — reachable without a token — holds.
        assertThat(response.getStatusCode()).isIn(HttpStatus.OK, HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getBody()).contains("status");
    }

    @Test
    @DisplayName("GET /movies is reachable with no token (public catalogue read)")
    void catalogueSearchIsPublic() {
        ResponseEntity<String> response = rest.getForEntity("/movies?size=1", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }
}
