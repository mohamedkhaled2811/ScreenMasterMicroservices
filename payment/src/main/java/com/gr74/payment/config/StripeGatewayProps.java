package com.gr74.payment.config;

import java.time.Duration;
import java.util.Set;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Config for the Stripe adapter ({@code payment.gateway.stripe.*}).
 *
 * <p>The whole class is only bound when {@code secret-key} is present — {@code StripeGateway} carries
 * the {@code @ConditionalOnProperty} — so an unconfigured deployment never registers the gateway.
 *
 * <p><b>The live-key guard is the important part.</b> A {@code sk_live_} key is rejected unless the
 * {@code production} profile is active, so this learning project is structurally incapable of moving
 * real money even if someone pastes the wrong key into {@code .env}.
 */
@ConfigurationProperties(prefix = "payment.gateway.stripe")
public record StripeGatewayProps(
        String secretKey,
        String webhookSecret,
        String apiBaseUrl,
        Set<String> supportedCurrencies,
        Duration sessionTtl,
        long signatureToleranceSeconds,
        boolean allowLiveKey) {

    private static final String LIVE_KEY_PREFIX = "sk_live_";

    public StripeGatewayProps {
        if (secretKey != null && secretKey.startsWith(LIVE_KEY_PREFIX) && !allowLiveKey) {
            throw new IllegalStateException("""
                    A LIVE Stripe secret key (sk_live_) was configured, but this project is a learning \
                    lab that must only ever touch test money. Use a sk_test_ key. If you genuinely \
                    intend to run against live Stripe, set payment.gateway.stripe.allow-live-key=true \
                    (only ever alongside spring.profiles.active=production).""");
        }
        if (apiBaseUrl == null || apiBaseUrl.isBlank()) {
            apiBaseUrl = "https://api.stripe.com";
        }
        if (supportedCurrencies == null || supportedCurrencies.isEmpty()) {
            supportedCurrencies = Set.of("USD");
        }
        if (sessionTtl == null || sessionTtl.isNegative() || sessionTtl.isZero()) {
            sessionTtl = Duration.ofMinutes(30);
        }
    }
}
