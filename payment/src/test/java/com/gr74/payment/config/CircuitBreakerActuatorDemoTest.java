package com.gr74.payment.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.math.BigDecimal;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.fasterxml.jackson.databind.ObjectMapper;

import com.gr74.payment.gateway.GatewayRegistry;
import com.gr74.payment.gateway.GatewaySessionRequest;
import com.gr74.payment.gateway.PaymentGateway;
import com.gr74.payment.model.PaymentGatewayType;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import lombok.extern.slf4j.Slf4j;

/**
 * Breaker state observed through the actuator endpoints on the full application context.
 */
@Slf4j
@AutoConfigureMockMvc
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        "payment.gateway.sandbox.unavailable-rate=1.0",
        "payment.gateway.sandbox.latency-millis=0",
        // A tiny window so the run is 5 calls, not 10, and no retry so 1 call == 1 breaker record.
        "resilience4j.circuitbreaker.instances.sandbox.sliding-window-size=5",
        "resilience4j.circuitbreaker.instances.sandbox.minimum-number-of-calls=5",
        "resilience4j.circuitbreaker.instances.sandbox.wait-duration-in-open-state=2s",
        "resilience4j.circuitbreaker.instances.sandbox.permitted-number-of-calls-in-half-open-state=2",
        "resilience4j.retry.instances.sandbox.max-attempts=1",
})
class CircuitBreakerActuatorDemoTest {

    @Autowired
    private GatewayRegistry registry;

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("actuator reports CLOSED -> OPEN as the sandbox gateway fails, and the open breaker fast-fails")
    void breakerStateIsVisibleThroughActuator() {
        PaymentGateway sandbox = registry.require(PaymentGatewayType.SANDBOX);

        log.info("=== breaker state through /actuator/circuitbreakers ===");
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
        // The event log shows the recorded errors and the state transition.
        log.info("events: {}", readActuator("/actuator/circuitbreakerevents?name=sandbox"));

        // The window filled, the breaker opened, and the remaining calls never reached the gateway.
        assertThat(state("sandbox")).isEqualTo("OPEN");
        assertThat(failures).isEqualTo(5);
        assertThat(fastFails).isEqualTo(3);

        // Stripe/Paymob are credential-less here, so only sandbox exists — per-gateway isolation
        // itself is covered by ResilientPaymentGatewayTest#bulkheadIsolatesPerGateway.
        assertThat(breakers()).containsKey("sandbox");
    }

    @Test
    @DisplayName("the resilience endpoints refuse anonymous callers with a coded 401")
    void resilienceEndpointsRequireAdmin() throws Exception {
        MvcResult result = mockMvc.perform(get("/actuator/circuitbreakers")).andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(401);
        assertThat(result.getResponse().getContentAsString()).contains("PAYMENT_UNAUTHORIZED");
    }

    private static GatewaySessionRequest sessionRequest() {
        return new GatewaySessionRequest(1L, 2L, 3L, "user-1", new BigDecimal("100.00"), "EGP",
                "idem-1", "http://localhost/return", "http://localhost/cancel");
    }

    /** Read an actuator endpoint as ADMIN — the MockMvc call runs the real chain + endpoint. */
    private String readActuator(String path) {
        try {
            MvcResult result = mockMvc.perform(get(path)
                            .with(jwt().jwt(jwt -> jwt.subject("admin"))
                                    .authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
                            .accept(MediaType.APPLICATION_JSON))
                    .andReturn();
            assertThat(result.getResponse().getStatus()).isEqualTo(200);
            return result.getResponse().getContentAsString();
        } catch (Exception e) {
            throw new AssertionError("actuator call failed: " + path, e);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Map<String, Object>> breakers() {
        try {
            Map<String, Object> body = new ObjectMapper()
                    .readValue(readActuator("/actuator/circuitbreakers"), Map.class);
            return (Map<String, Map<String, Object>>) body.get("circuitBreakers");
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new AssertionError("unparseable /actuator/circuitbreakers body", e);
        }
    }

    private String state(String name) {
        return (String) breakers().get(name).get("state");
    }
}
