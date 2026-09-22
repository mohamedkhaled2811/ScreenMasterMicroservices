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
 * Public health endpoint and coded 401 for unauthenticated booking reads, over real HTTP.
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

        // Either 2xx or 5xx satisfies the gate: the endpoint answered instead of rejecting.
        assertThat(response.getStatusCode()).isIn(HttpStatus.OK, HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getBody()).contains("status");
    }

    @Test
    @DisplayName("GET /bookings/my with no token is a coded 401")
    void myBookingsWithoutTokenIs401() {
        ResponseEntity<String> response = rest.getForEntity("/bookings/my", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody()).contains("BOOKING_UNAUTHORIZED");
    }
}
