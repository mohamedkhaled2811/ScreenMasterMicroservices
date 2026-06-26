package com.gr74.notification;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Smoke test: proves the notification application context wires up cleanly.
 *
 * <p>Kept hermetic by {@code src/test/resources/application.yml}, which disables Eureka
 * registration so the test needs no running discovery server. Random port avoids clashing with a
 * notification instance already bound to 8084 locally.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class NotificationApplicationTests {

    @Test
    void contextLoads() {
        // If the context fails to start, this test fails — that's the assertion.
    }
}
