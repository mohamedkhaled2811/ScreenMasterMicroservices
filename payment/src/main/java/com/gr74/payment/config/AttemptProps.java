package com.gr74.payment.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Config for the payment-attempt lifecycle ({@code payment.attempt.*}).
 *
 * <p>{@code strandedTtl} bounds how long a <em>session-less</em> attempt may wedge its payment.
 * When the gateway throws on {@code createSession}, the attempt row is deliberately left
 * {@code PENDING} (the gateway may have created a session we never learned about), and a retry
 * reuses that row instead of inserting a new one. But a row that is never retried must not block
 * its payment forever, so it carries a provisional deadline: once past it, the attempt-expiry
 * sweeper closes it and the payment is payable again. It defaults to longer than
 * {@code payment.reconciliation.stale-after} (10m) so the "reconciliation decides first" ordering
 * keeps its meaning for attempts that actually have a gateway session to ask about.
 */
@ConfigurationProperties(prefix = "payment.attempt")
public record AttemptProps(
        Duration strandedTtl) {

    public AttemptProps {
        if (strandedTtl == null || strandedTtl.isNegative() || strandedTtl.isZero()) {
            strandedTtl = Duration.ofMinutes(15);
        }
    }
}
