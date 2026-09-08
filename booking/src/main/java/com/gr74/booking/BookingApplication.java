package com.gr74.booking;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Booking service — the core of the system (it absorbs Theater + Scheduling). Owns showtimes,
 * seats, and bookings in its own Postgres (booking-db).
 *
 * <p>A Eureka <b>client</b> (the starter auto-registers; no {@code @EnableEurekaClient} needed). It
 * will call Payment and Catalog synchronously via {@code RestClient} and run the booking saga,
 * outbox, and resilience wiring across Phases 3-5. Schema is managed by Liquibase with
 * {@code ddl-auto=validate} — see {@code docs/concepts/liquibase.md}.
 *
 * <p>Real tables and endpoints arrive in Phase 3; right now the app boots against an empty
 * (Liquibase-tracked) schema and exposes only {@code /actuator/health}.
 *
 * <p>{@code @EnableScheduling} drives the hold-expiry sweeper (BUILD_PLAN 3.4).
 */
@SpringBootApplication
@EnableScheduling
public class BookingApplication {

    public static void main(String[] args) {
        SpringApplication.run(BookingApplication.class, args);
    }
}
