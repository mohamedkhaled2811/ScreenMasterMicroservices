package com.gr74.booking.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Outbox relay settings ({@code booking.outbox.*}) with safe defaults.
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