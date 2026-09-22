package com.gr74.payment.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Service-wide payment config ({@code payment.*}): the public base URL for return/cancel links.
 */
@ConfigurationProperties(prefix = "payment")
public record PaymentProps(String publicUrl) {

    public PaymentProps {
        if (publicUrl == null || publicUrl.isBlank()) {
            publicUrl = "http://localhost:8080";
        }
        // Trailing slashes would produce "//payments/..." when paths are appended.
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
