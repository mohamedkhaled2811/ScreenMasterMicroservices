package com.gr74.payment.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Config for the recovery side of stale payments ({@code payment.reconciliation.*}).
 *
 * <p>{@code staleAfter} is the single shared definition of "old enough that the gateway has had
 * its chance": step 3.6's reconciliation sweep probes attempts <em>stale for longer than
 * this</em> against the gateway, and step 3.4's {@code AttemptExpirySweeper} <em>skips</em>
 * attempts younger than this so blind expiry never wins the race against the gateway check. One
 * property, two readers — that sharing is the whole point, so 3.6 reuses this exact value rather
 * than introducing its own threshold.
 *
 * <p>{@code batchSize} bounds one sweep's probe count so the first tick after a long outage walks
 * the backlog over several passes instead of one enormous transaction-per-item loop.
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
