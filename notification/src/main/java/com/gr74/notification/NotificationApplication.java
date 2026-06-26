package com.gr74.notification;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Notification service.
 *
 * <p>In Phase 4 this becomes a pure event consumer: it listens for {@code BookingConfirmed} off
 * RabbitMQ, "sends" the email (a log line), and dedupes via a {@code processed_events} table (the
 * idempotent-consumer pattern). None of that exists yet.
 *
 * <p>For now (1.2) it just registers with Eureka — a Eureka <b>client</b> ({@code @EnableEurekaClient}
 * is not needed; the starter auto-registers) — and exposes {@code /actuator/health}. It carries
 * web-mvc only to advertise a port and serve that health endpoint; see the {@code pom.xml} note and
 * {@code docs/concepts/service-discovery.md}.
 */
@SpringBootApplication
public class NotificationApplication {

    public static void main(String[] args) {
        SpringApplication.run(NotificationApplication.class, args);
    }
}
