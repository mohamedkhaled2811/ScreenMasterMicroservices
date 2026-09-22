package com.gr74.payment.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Config for the payment-attempt lifecycle ({@code payment.attempt.*}).
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
