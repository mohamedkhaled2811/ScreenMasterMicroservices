package com.gr74.payment.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.TestPropertySource;

import com.fasterxml.jackson.databind.ObjectMapper;

import com.gr74.payment.gateway.GatewayRegistry;
import com.gr74.payment.gateway.GatewaySessionRequest;
import com.gr74.payment.gateway.PaymentGateway;
import com.gr74.payment.model.PaymentGatewayType;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import lombok.extern.slf4j.Slf4j;

/**
 * BUILD_PLAN 5.2 — the breaker observed through {@code /actuator/circuitbreakers} on the REAL
 * application context, not a hand-built registry.
 *
 * <p>This is the difference from {@link com.gr74.payment.gateway.ResilientPaymentGatewayTest}: that
 * suite proves the policy semantics with registries it constructs itself, so it can never catch a
 * broken {@code resilience4j.*} YAML binding or an unexposed actuator endpoint. This one boots the
 * whole service, drives the SANDBOX gateway through {@link GatewayRegistry} (so the call path is
 * exactly production's), and reads the state back over HTTP the way the demo does.
 *
 * <p>The sandbox is pinned to {@code unavailable-rate=1.0} so every call throws
 * {@code GatewayException} — the only exception the breaker records.
 */
@Slf4j
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        "payment.gateway.sandbox.unavailable-rate=1.0",
        "payment.gateway.sandbox.latency-millis=0",
        // A tiny window so the demo is 5 calls, not 10, and no retry so 1 call == 1 breaker record.
        "resilience4j.circuitbreaker.instances.sandbox.sliding-window-size=5",
        "resilience4j.circuitbreaker.instances.sandbox.minimum-number-of-calls=5",
        "resilience4j.circuitbreaker.instances.sandbox.wait-duration-in-open-state=2s",
        "resilience4j.circuitbreaker.instances.sandbox.permitted-number-of-calls-in-half-open-state=2",
        "resilience4j.retry.instances.sandbox.max-attempts=1",
})
class CircuitBreakerActuatorDemoTest {

    @Autowired
    private GatewayRegistry registry;

    @LocalServerPort
    private int port;

    @Test
    @DisplayName("actuator reports CLOSED -> OPEN as the sandbox gateway fails, and the open breaker fast-fails")
    void breakerStateIsVisibleThroughActuator() {
        PaymentGateway sandbox = registry.require(PaymentGatewayType.SANDBOX);

        log.info("=== BUILD_PLAN 5.2 — breaker state through /actuator/circuitbreakers ===");
        log.info("initial state: {}", state("sandbox"));
        assertThat(state("sandbox")).isEqualTo("CLOSED");

        // Fill the 5-call window with real GatewayExceptions from the sandbox adapter.
        int failures = 0;
        int fastFails = 0;
        for (int i = 1; i <= 8; i++) {
            try {
                sandbox.createSession(sessionRequest());
                log.info("call {} -> unexpected success", i);
            } catch (CallNotPermittedException e) {
                fastFails++;
                log.info("call {} -> FAST-FAIL (breaker open, gateway never touched) state={}",
                        i, state("sandbox"));
            } catch (RuntimeException e) {
                failures++;
                log.info("call {} -> {} state={}", i, e.getClass().getSimpleName(), state("sandbox"));
            }
        }

        log.info("summary: {} real gateway failures, {} fast-fails, final state={}",
                failures, fastFails, state("sandbox"));
        // The event log is the demo's money shot: the recorded errors and the state transition.
        log.info("events: {}", get("/actuator/circuitbreakerevents?name=sandbox"));

        // The window filled, the breaker opened, and the remaining calls never reached the gateway.
        assertThat(state("sandbox")).isEqualTo("OPEN");
        assertThat(failures).isEqualTo(5);
        assertThat(fastFails).isEqualTo(3);

        // Stripe/Paymob are credential-less here, so only sandbox exists — per-gateway isolation
        // itself is covered by ResilientPaymentGatewayTest#bulkheadIsolatesPerGateway.
        assertThat(breakers()).containsKey("sandbox");
    }

    private static GatewaySessionRequest sessionRequest() {
        return new GatewaySessionRequest(1L, 2L, 3L, "user-1", new BigDecimal("100.00"), "EGP",
                "idem-1", "http://localhost/return", "http://localhost/cancel");
    }

    /** Read an actuator endpoint over real HTTP — the JDK client, so no extra test dependency. */
    private String get(String path) {
        try {
            HttpResponse<String> response = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(200);
            return response.body();
        } catch (IOException e) {
            throw new AssertionError("actuator call failed: " + path, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted calling " + path, e);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Map<String, Object>> breakers() {
        try {
            Map<String, Object> body = new ObjectMapper()
                    .readValue(get("/actuator/circuitbreakers"), Map.class);
            return (Map<String, Map<String, Object>>) body.get("circuitBreakers");
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new AssertionError("unparseable /actuator/circuitbreakers body", e);
        }
    }

    private String state(String name) {
        return (String) breakers().get(name).get("state");
    }
}
