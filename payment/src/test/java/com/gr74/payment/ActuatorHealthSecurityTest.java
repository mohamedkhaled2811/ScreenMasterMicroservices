package com.gr74.payment;

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
 * Unauthenticated boundaries over real HTTP: public health endpoint, coded 401 elsewhere.
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
        // The exact 2xx/5xx depends on backing systems, not auth: with no broker here
        // downstream indicators report DOWN and health is 503; with everything up it is 200 UP.
        assertThat(response.getStatusCode()).isIn(HttpStatus.OK, HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getBody()).contains("status");
    }

    @Test
    @DisplayName("POST /payments with no token is a coded 401")
    void createSessionWithoutTokenIs401() {
        ResponseEntity<String> response = rest.postForEntity("/payments",
                new TestPaymentBody(1001L, "SANDBOX"), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody()).contains("PAYMENT_UNAUTHORIZED");
    }

    /** Minimal {@code CreatePaymentRequest} shape — a record serializes straight to JSON. */
    record TestPaymentBody(Long bookingId, String gateway) {
    }
}
