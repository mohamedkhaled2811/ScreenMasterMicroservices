package com.gr74.booking.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Config for the transactional-outbox relay ({@code booking.outbox.*}).
 *
 * <p>{@code relayIntervalMillis} is how often the drain tick runs: short enough that a confirmed
 * booking feels instant to the user waiting on Notification's email, long enough that an idle
 * service is not hammering the database. {@code relayBatchSize} bounds one tick's claim so the
 * first tick after a long outage walks the backlog over several passes instead of one enormous
 * publish loop. Mirrors {@code payment}'s {@code OutboxProps}; bound by
 * {@code @ConfigurationPropertiesScan} on {@code BookingApplication}.
 */
@ConfigurationProperties(prefix = "booking.outbox")
public record OutboxProps(
        long relayIntervalMillis,
        int relayBatchSize) {

    public OutboxProps {
        if (relayIntervalMillis <= 0) {
            relayIntervalMillis = 2000;
        }
        if (relayBatchSize <= 0) {
            relayBatchSize = 50;
        }
    }
}