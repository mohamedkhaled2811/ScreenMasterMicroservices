package com.gr74.payment.config;

import java.time.Duration;
import java.util.Set;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Config for the Paymob adapter ({@code payment.gateway.paymob.*}).
 *
 * <p>Bound only when {@code api-key} is present ({@code PaymobGateway} carries the
 * {@code @ConditionalOnProperty}), so a deployment without Paymob credentials never offers it.
 *
 * <p>{@code integrationId} and {@code iframeId} come from the Paymob dashboard and identify which
 * payment method and which hosted checkout page to use — Paymob has no equivalent of Stripe's single
 * "Checkout" endpoint, so both must be configured explicitly.
 */
@ConfigurationProperties(prefix = "payment.gateway.paymob")
public record PaymobGatewayProps(
        String apiKey,
        String hmacSecret,
        String integrationId,
        String iframeId,
        String apiBaseUrl,
        String iframeBaseUrl,
        Set<String> supportedCurrencies,
        Duration sessionTtl) {

    public PaymobGatewayProps {
        if (apiBaseUrl == null || apiBaseUrl.isBlank()) {
            apiBaseUrl = "https://accept.paymob.com";
        }
        if (iframeBaseUrl == null || iframeBaseUrl.isBlank()) {
            iframeBaseUrl = "https://accept.paymob.com/api/acceptance/iframes";
        }
        if (supportedCurrencies == null || supportedCurrencies.isEmpty()) {
            supportedCurrencies = Set.of("EGP");
        }
        if (sessionTtl == null || sessionTtl.isNegative() || sessionTtl.isZero()) {
            sessionTtl = Duration.ofMinutes(15);
        }
    }
}
