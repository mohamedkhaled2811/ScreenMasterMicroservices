package com.gr74.discovery;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Smoke test: proves the Eureka-server application context wires up cleanly.
 *
 * <p>It catches the most common break for a config-only module — a mis-wired context or invalid
 * {@code application.yml} — in the reactor build rather than only at runtime. We bind to a random
 * port ({@code server.port=0}) so the test never clashes with a discovery server already running
 * on 8761 locally.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class DiscoveryApplicationTests {

    @Test
    void contextLoads() {
        // If the @EnableEurekaServer context fails to start, this test fails — that's the assertion.
    }
}
