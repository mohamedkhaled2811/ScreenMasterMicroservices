package com.gr74.notification;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Notification service.
 *
 * <p>Since Phase 4.3 this is a real event consumer: it listens for {@code BookingConfirmed} and
 * {@code BookingConfirmationRejected} off RabbitMQ and dedupes via a {@code processed_events} table —
 * the idempotent-consumer pattern. See {@code docs/concepts/idempotent-consumer.md}.
 *
 * <p>The send is now a REAL side effect: an HTML ticket email over SMTP, rendered from the facts
 * Booking snapshots onto the event and addressed to the email looked up from Keycloak. See
 * {@code docs/concepts/notification-channels.md}.
 *
 * <p>{@code @ConfigurationPropertiesScan} binds the {@code notification.*} tree into
 * {@link com.gr74.notification.config.NotificationProps} without an explicit enable-annotation —
 * the {@code PaymentApplication} / {@code BookingApplication} idiom.
 *
 * <p>It also registers with Eureka — a Eureka <b>client</b> ({@code @EnableEurekaClient} is not
 * needed; the starter auto-registers) — and exposes {@code /actuator/health}. It carries web-mvc
 * only to advertise a port and serve that health endpoint; see the {@code pom.xml} note and
 * {@code docs/concepts/service-discovery.md}.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class NotificationApplication {

    public static void main(String[] args) {
        SpringApplication.run(NotificationApplication.class, args);
    }
}
