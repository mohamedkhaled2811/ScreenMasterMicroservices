package com.gr74.payment.gateway;

import java.time.Instant;

/**
 * Gateway checkout session, normalized. expiresAt is the session clock, not the booking hold.
 *
 * @param gatewaySessionId gateway session id; webhook correlation key
 * @param checkoutUrl      user redirect target
 * @param expiresAt        session expiry, or null when the gateway does not say
 */
public record GatewaySession(String gatewaySessionId, String checkoutUrl, Instant expiresAt) {
}
