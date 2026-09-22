package com.gr74.payment.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Config for stale-payment recovery ({@code payment.reconciliation.*}).
 */
@ConfigurationProperties(prefix = "payment.reconciliation")
public record ReconciliationProps(
        Duration staleAfter,
        Integer batchSize) {

    public ReconciliationProps {
        if (staleAfter == null || staleAfter.isNegative() || staleAfter.isZero()) {
            staleAfter = Duration.ofMinutes(10);
        }
        if (batchSize == null || batchSize <= 0) {
            batchSize = 100;
        }
    }
}
