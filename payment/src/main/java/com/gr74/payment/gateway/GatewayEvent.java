package com.gr74.payment.gateway;

import com.gr74.payment.model.PaymentAttemptStatus;

/**
 * A verified webhook, parsed and normalized by the adapter into terms the rest of the service
 * understands.
 *
 * <p>This is the boundary where gateway vocabulary stops. Paymob's {@code success} and Stripe's
 * {@code checkout.session.completed} both arrive here as {@link PaymentAttemptStatus#SUCCEEDED}, and
 * nothing downstream ever branches on a gateway's own words.
 *
 * @param eventId          the gateway's OWN event id — the dedupe key
 * @param rawEventType     the gateway's raw type string, kept for storage and forensics only
 * @param gatewaySessionId which session this is about; how we find the attempt
 * @param gatewayPaymentId the gateway's transaction id, when present
 * @param status           normalized outcome, or {@code null} for an event we do not act on
 * @param failureReason    normalized reason when the status is a failure
 */
public record GatewayEvent(
        String eventId,
        String rawEventType,
        String gatewaySessionId,
        String gatewayPaymentId,
        PaymentAttemptStatus status,
        String failureReason) {

    /**
     * True when this event carries no outcome we act on (a gateway emits many types; we care about
     * a few). Such events are still stored and still answered 200 — they are simply IGNORED.
     */
    public boolean isActionable() {
        return status != null;
    }
}
