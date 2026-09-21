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

/**
 * Payment-gateway webhooks must cross the edge WITHOUT a token.
 *
 * <p>Paymob, Stripe and the sandbox deliver from the public internet holding no JWT and never
 * will: these requests are authenticated by <b>HMAC signature over the raw body</b>, verified in
 * Payment — which already permits the path. The gateway has to agree, or the delivery is rejected
 * at the edge and Payment never sees it.
 *
 * <p>This pins a bug that reached a live ngrok tunnel: with no webhook rule in the gateway's
 * {@code SecurityConfig}, the path fell through to {@code anyRequest().authenticated()} and every
 * real Paymob callback got a <b>401</b>, while the same request sent straight to Payment answered
 * 400 (bad signature — i.e. it arrived). Payment was correct; the edge was the blocker.
 *
 * <p><b>A second bug this uncovered.</b> Spring forwards to {@code /error} on any downstream
 * failure, and that FORWARD is authorized again as its own dispatch — so a routing failure on a
 * PUBLIC path surfaced as a misleading 401 "authentication required" rather than the real error.
 * {@code SecurityConfig} now permits the ERROR/FORWARD dispatch types, which is why this test can
 * assert the status directly. The live system confirms the whole path: a real unauthenticated call
 * now reaches Payment and returns {@code PAYMENT_WEBHOOK_SIGNATURE_INVALID}.
 */
@AutoConfigureTestRestTemplate
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class WebhookRouteSecurityTest {

    @Autowired
    private TestRestTemplate rest;

    @Test
    @DisplayName("an unauthenticated webhook is not rejected BY THE EDGE")
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
        // Scoping check: written as /api/payments/** instead of /api/payments/webhooks/**, this
        // rule would silently make the whole payment API public.
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
