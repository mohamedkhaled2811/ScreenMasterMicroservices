package com.gr74.payment.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Config for the transactional-outbox relay ({@code payment.outbox.*}).
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
