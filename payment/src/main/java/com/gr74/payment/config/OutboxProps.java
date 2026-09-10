package com.gr74.payment.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Config for the transactional-outbox relay ({@code payment.outbox.*}).
 *
 * <p>{@code relayIntervalMillis} is how often the drain tick runs: short enough that a confirmed
 * payment feels instant to the user waiting on Booking, long enough that an idle service is not
 * hammering the database. {@code relayBatchSize} bounds one tick's claim so the first tick after a
 * long outage walks the backlog over several passes instead of one enormous publish loop.
 */
@ConfigurationProperties(prefix = "payment.outbox")
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
