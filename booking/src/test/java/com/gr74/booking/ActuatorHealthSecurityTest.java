package com.gr74.booking;

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
 * The unauthenticated boundaries, proven over real HTTP against the full application (real filter
 * chain, real actuator):
 * <ul>
 *   <li>{@code GET /actuator/health} answers without a token. Compose gates service startup on
 *       this endpoint; if it ever started 401-ing, {@code depends_on: service_healthy} would hang
 *       the whole stack in a failure that looks completely unrelated to auth.</li>
 *   <li>{@code GET /bookings/my} without a token is {@code 401 BOOKING_UNAUTHORIZED} with a coded
 *       ProblemDetail body — the zero-trust proof at the service port (not just the gateway).</li>
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
    @DisplayName("GET /bookings/my with no token is a coded 401 (zero trust at the service port)")
    void myBookingsWithoutTokenIs401() {
        ResponseEntity<String> response = rest.getForEntity("/bookings/my", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody()).contains("BOOKING_UNAUTHORIZED");
    }
}
