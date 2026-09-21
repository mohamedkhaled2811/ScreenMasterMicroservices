package com.gr74.gateway;

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
 * The edge's unauthenticated boundary, proven over real HTTP against the full gateway (real
 * filter chain, real routes, real actuator): {@code GET /actuator/health} answers without a
 * token. A gated health endpoint hangs orchestration probes — the failure that looks completely
 * unrelated to auth.
 *
 * <p>Hermetic by construction: Eureka is off (same test yml as the smoke test) and the lazy
 * {@code JwtDecoder} is never invoked without a token — no discovery server, no Keycloak needed.
 * This test also proves the gateway wiring (routes + {@code RemoveRequestHeader} filters
 * + security) parses and boots, the way the smoke test does for routes alone.
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
    @DisplayName("GET /actuator/health is reachable with no token")
    void healthIsPublic() {
        ResponseEntity<String> response = rest.getForEntity("/actuator/health", String.class);

        // The SECURITY assertion is "not 401/403" — the endpoint answered instead of rejecting.
        assertThat(response.getStatusCode()).isIn(HttpStatus.OK, HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getBody()).contains("status");
    }
}
