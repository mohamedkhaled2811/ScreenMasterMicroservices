package com.gr74.payment.gateway;

import java.time.Instant;

/**
 * What a gateway returns when it opens a checkout session — normalized into our terms.
 *
 * @param gatewaySessionId the gateway's id for this session; the key the webhook is matched on
 * @param checkoutUrl      where the client redirects the user
 * @param expiresAt        when this SESSION lapses. Deliberately a different clock from the booking
 *                         hold: a lapsed session is recoverable ("Pay Again"), a lapsed booking is
 *                         not. May be {@code null} when a gateway does not say, in which case our
 *                         own configured TTL applies.
 */
public record GatewaySession(String gatewaySessionId, String checkoutUrl, Instant expiresAt) {
}
