package com.gr74.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/** Payment webhooks cross the edge without a token (HMAC-verified in Payment). */
@AutoConfigureTestRestTemplate
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class WebhookRouteSecurityTest {

    @Autowired
    private TestRestTemplate rest;

    @Test
    @DisplayName("an unauthenticated webhook is not rejected by the edge")
    void webhooksAreNotBlockedAtTheEdge() {
        ResponseEntity<String> response = post("/api/payments/webhooks/paymob");

        assertThat(response.getStatusCode())
                .as("the edge must not reject an unauthenticated Paymob webhook")
                .isNotEqualTo(HttpStatus.UNAUTHORIZED)
                .isNotEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("the webhook exemption does not leak to the rest of the payment API")
    void otherPaymentPathsStillRequireAToken() {
        ResponseEntity<String> response = post("/api/payments/sessions");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody())
                .as("a real edge rejection carries the coded ProblemDetail")
                .contains("GATEWAY_UNAUTHORIZED");
    }

    private ResponseEntity<String> post(String path) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return rest.postForEntity(path, new HttpEntity<>("{\"id\":\"evt_test\"}", headers), String.class);
    }
}
