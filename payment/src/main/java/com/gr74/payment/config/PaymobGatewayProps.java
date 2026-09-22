package com.gr74.payment.config;

import java.time.Duration;
import java.util.Set;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Config for the Paymob adapter ({@code payment.gateway.paymob.*}). Bound only when credentials exist.
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
