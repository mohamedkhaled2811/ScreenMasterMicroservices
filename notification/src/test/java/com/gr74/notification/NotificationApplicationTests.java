package com.gr74.notification;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Smoke test: proves the notification application context wires up cleanly.
 *
 * <p>Kept hermetic by {@code src/test/resources/application.yml}: Eureka registration is disabled,
 * the datasource points at in-memory H2 (with Liquibase off, Hibernate owning the test schema), and
 * the {@code @RabbitListener} container is stopped ({@code notification.amqp.listener.auto-startup}),
 * so the test needs no running Eureka, Postgres, or RabbitMQ. Random port avoids clashing with a
 * notification instance already bound to 8084 locally.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class NotificationApplicationTests {

    @Test
    void contextLoads() {
        // If the context fails to start, this test fails — that's the assertion.
    }
}
