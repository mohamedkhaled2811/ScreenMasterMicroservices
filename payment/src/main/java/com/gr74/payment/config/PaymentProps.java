package com.gr74.payment.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Service-wide payment config ({@code payment.*}).
 *
 * <p>{@code publicUrl} is the base a gateway sends the user back to, and the base our webhook URLs
 * are built from. It is configuration rather than a constant because gateways cannot reach
 * {@code localhost}: in local development this points at a Stripe CLI forward or an ngrok tunnel, and
 * in a deployment at the real public host.
 */
@ConfigurationProperties(prefix = "payment")
public record PaymentProps(String publicUrl) {

    public PaymentProps {
        if (publicUrl == null || publicUrl.isBlank()) {
            publicUrl = "http://localhost:8080";
        }
        // Trailing slashes would produce "//payments/..." when we append paths.
        publicUrl = publicUrl.endsWith("/") ? publicUrl.substring(0, publicUrl.length() - 1) : publicUrl;
    }

    /** Where the gateway returns a user after a completed checkout (UX only — never authoritative). */
    public String returnUrl(Long attemptId) {
        return publicUrl + "/api/payments/return?attemptId=" + attemptId;
    }

    /** Where the gateway returns a user who abandoned checkout. */
    public String cancelUrl(Long attemptId) {
        return publicUrl + "/api/payments/cancel?attemptId=" + attemptId;
    }
}
