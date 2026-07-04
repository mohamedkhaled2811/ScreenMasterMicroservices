package com.gr74.booking.config;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Provides a system {@link Clock} as a bean so time-dependent logic (here, "which showtimes are
 * upcoming") reads the current date through an injected clock rather than a static
 * {@code LocalDate.now()}. That makes "today" substitutable in a test (a fixed clock), and it will
 * matter more for the Phase-3 expiry sweeper, which is entirely about the passage of time.
 */
@Configuration
public class ClockConfig {

    @Bean
    public Clock clock() {
        return Clock.systemDefaultZone();
    }
}
