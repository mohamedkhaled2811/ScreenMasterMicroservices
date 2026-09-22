package com.gr74.payment.config;

import java.time.Duration;
import java.util.Set;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Config for the controllable {@code SANDBOX} gateway ({@code payment.gateway.sandbox.*}).
 */
@ConfigurationProperties(prefix = "payment.gateway.sandbox")
public record SandboxGatewayProps(
        Set<String> supportedCurrencies,
        double failureRate,
        double unavailableRate,
        long latencyMillis,
        Duration sessionTtl,
        String checkoutBaseUrl,
        String webhookSecret) {

    public SandboxGatewayProps {
        if (supportedCurrencies == null || supportedCurrencies.isEmpty()) {
            supportedCurrencies = Set.of("EGP", "USD");
        }
        requireRate(failureRate, "payment.gateway.sandbox.failure-rate");
        requireRate(unavailableRate, "payment.gateway.sandbox.unavailable-rate");
        if (latencyMillis < 0) {
            throw new IllegalArgumentException("payment.gateway.sandbox.latency-millis must be >= 0");
        }
        if (sessionTtl == null || sessionTtl.isNegative() || sessionTtl.isZero()) {
            throw new IllegalArgumentException("payment.gateway.sandbox.session-ttl must be positive");
        }
        if (webhookSecret == null || webhookSecret.isBlank()) {
            throw new IllegalArgumentException("payment.gateway.sandbox.webhook-secret must be set");
        }
    }

    private static void requireRate(double value, String key) {
        if (value < 0.0 || value > 1.0) {
            throw new IllegalArgumentException(key + " must be in [0.0, 1.0] but was " + value);
        }
    }
}
