package com.gr74.payment.config;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Provides a system {@link Clock} as a bean so time-dependent logic (here, the attempt-expiry
 * sweeper's "is this session past its deadline, and past the reconciliation window?") reads the
 * current instant through an injected clock rather than a static {@code Instant.now()}. That makes
 * "now" substitutable in a test (a fixed clock) — the sweeper is entirely about the passage of
 * time. Mirrors Booking's {@code ClockConfig}.
 */
@Configuration
public class ClockConfig {

    @Bean
    public Clock clock() {
        return Clock.systemDefaultZone();
    }
}
