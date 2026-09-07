package com.gr74.payment.gateway;

import com.gr74.payment.model.PaymentAttemptStatus;
import com.gr74.payment.model.RefundStatus;

/**
 * A verified webhook, parsed and normalized by the adapter into terms the rest of the service
 * understands.
 *
 * <p>This is the boundary where gateway vocabulary stops. Paymob's {@code success} and Stripe's
 * {@code checkout.session.completed} both arrive here as {@link PaymentAttemptStatus#SUCCEEDED}, and
 * nothing downstream ever branches on a gateway's own words.
 *
 * <p>A webhook carries exactly one kind of outcome ({@link GatewayEventKind}): a <b>payment</b>
 * outcome settles an attempt (and possibly its payment) via {@code PaymentWriter.applyOutcome}; a
 * <b>refund</b> outcome confirms or rejects a refund row via
 * {@code PaymentWriter.markRefundReceived} — correlated by the gateway's refund id, because a
 * gateway like Stripe does not echo the checkout session on refund events. Both kinds share the
 * same evidence store, the same {@code UNIQUE (gateway, event_id)} dedupe, and the same
 * 200-to-almost-everything rule — only the terminal handler differs.
 *
 * @param eventId          the gateway's OWN event id — the dedupe key
 * @param rawEventType     the gateway's raw type string, kept for storage and forensics only
 * @param gatewaySessionId which session this is about; how we find the attempt (may be null on
 *                         refund events whose gateway does not echo the session — step 3.5 resolves
 *                         those against the refund ledger instead)
 * @param gatewayPaymentId the gateway's transaction id, when present
 * @param status           normalized payment outcome, or {@code null} for an event we do not act on
 * @param failureReason    normalized reason when the status is a failure
 * @param kind             whether this is a payment or a refund outcome
 * @param refundStatus     normalized refund outcome when {@code kind} is {@code REFUND}
 * @param gatewayRefundId  the gateway's refund id, when present
 */
public record GatewayEvent(
        String eventId,
        String rawEventType,
        String gatewaySessionId,
        String gatewayPaymentId,
        PaymentAttemptStatus status,
        String failureReason,
        GatewayEventKind kind,
        RefundStatus refundStatus,
        String gatewayRefundId) {

    /**
     * The original six-argument shape, kept so every payment-vocabulary call site reads unchanged:
     * a payment event with no refund fields. New refund vocabulary uses the full constructor.
     */
    public GatewayEvent(
            String eventId,
            String rawEventType,
            String gatewaySessionId,
            String gatewayPaymentId,
            PaymentAttemptStatus status,
            String failureReason) {
        this(eventId, rawEventType, gatewaySessionId, gatewayPaymentId, status, failureReason,
                GatewayEventKind.PAYMENT, null, null);
    }

    /**
     * True when this event carries no outcome we act on (a gateway emits many types; we care about
     * a few). Such events are still stored and still answered 200 — they are simply IGNORED.
     */
    public boolean isActionable() {
        return kind == GatewayEventKind.PAYMENT && status != null;
    }

    /** True when this event describes a refund rather than a payment outcome. */
    public boolean isRefund() {
        return kind == GatewayEventKind.REFUND;
    }
}
