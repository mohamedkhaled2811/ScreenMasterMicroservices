package com.gr74.gateway;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Smoke test: proves the gateway application context wires up cleanly, including the route
 * definitions parsed from {@code application.yml} (a malformed route fails the context here, in the
 * reactor build, rather than only at runtime).
 *
 * <p>Kept hermetic by {@code src/test/resources/application.yml}: Eureka registration is disabled so
 * the test needs no running discovery server. Random port avoids clashing with a gateway instance
 * already bound to 8080 locally.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class GatewayApplicationTests {

    @Test
    void contextLoads() {
        // If the context fails to start, this test fails — that's the assertion.
    }
}
