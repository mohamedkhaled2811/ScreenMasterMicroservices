package com.gr74.notification;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/** Proves GET /actuator/health is reachable without a token. */
@AutoConfigureTestRestTemplate
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ActuatorHealthSecurityTest {

    @Autowired
    private TestRestTemplate rest;

    @Test
    @DisplayName("GET /actuator/health is reachable with no token (compose healthchecks depend on it)")
    void healthIsPublic() {
        ResponseEntity<String> response = rest.getForEntity("/actuator/health", String.class);

        // The security assertion is "not 401/403"; the exact 2xx/5xx depends on backing systems.
        assertThat(response.getStatusCode()).isIn(HttpStatus.OK, HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getBody()).contains("status");
    }
}
