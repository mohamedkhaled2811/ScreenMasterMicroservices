package com.gr74.booking;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Smoke test: proves the booking application context wires up cleanly.
 *
 * <p>Kept hermetic by {@code src/test/resources/application.yml}: Eureka registration is disabled
 * and the datasource points at in-memory H2 (with Liquibase off, Hibernate owning the test schema),
 * so the test needs neither a running Eureka nor a Postgres. Random port avoids clashing with a
 * booking instance already bound to 8082 locally.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class BookingApplicationTests {

    @Test
    void contextLoads() {
        // If the context fails to start, this test fails — that's the assertion.
    }
}
