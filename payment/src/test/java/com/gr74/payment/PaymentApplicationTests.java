package com.gr74.payment;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Smoke test: proves the payment application context wires up cleanly (controller, service,
 * provider, config binding).
 *
 * <p>Kept hermetic by {@code src/test/resources/application.yml}: Eureka registration is disabled
 * and the datasource/JPA/Liquibase auto-config is excluded, so the test needs neither a running
 * Eureka nor a Postgres — it runs in CI with nothing else up. Random port avoids clashing with a
 * payment instance already bound to 8083 locally.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PaymentApplicationTests {

    @Test
    void contextLoads() {
        // If the context fails to start, this test fails — that's the assertion.
    }
}
