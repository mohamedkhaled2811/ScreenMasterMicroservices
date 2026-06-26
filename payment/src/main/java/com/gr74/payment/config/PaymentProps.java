package com.gr74.payment.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Typed binding for the {@code payment.*} config keys (see {@code application.yml}).
 *
 * <p>{@code failRate} is wired from the {@code FAIL_RATE} env var so we can dial in payment
 * failures per run without touching code — {@code 0.0} = always approve, {@code 1.0} = always
 * decline. {@code latencyMillis} simulates a real provider's round-trip (~300ms) so Booking's
 * timeout/retry behaviour (Phase 5) has something real to bite on.
 *
 * <p>A {@code record} is the idiomatic immutable holder for config; binding is constructor-based.
 * See {@code docs/concepts/spring-boot-annotations.md} (@ConfigurationProperties).
 */
@ConfigurationProperties(prefix = "payment")
public record PaymentProps(double failRate, long latencyMillis) {

    public PaymentProps {
        if (failRate < 0.0 || failRate > 1.0) {
            throw new IllegalArgumentException("payment.fail-rate must be in [0.0, 1.0] but was " + failRate);
        }
        if (latencyMillis < 0) {
            throw new IllegalArgumentException("payment.latency-millis must be >= 0 but was " + latencyMillis);
        }
    }
}
