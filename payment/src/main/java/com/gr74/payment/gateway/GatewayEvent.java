package com.gr74.payment.gateway;

import com.gr74.payment.model.PaymentAttemptStatus;
import com.gr74.payment.model.RefundStatus;

/**
 * Verified webhook, normalized. Payment outcomes settle attempts; refund outcomes settle refund rows.
 *
 * @param eventId          gateway's own event id — dedupe key
 * @param rawEventType     gateway raw type, storage only
 * @param gatewaySessionId session this is about; null on some refund events
 * @param gatewayPaymentId gateway transaction id, when present
 * @param status           normalized payment outcome, null when not actionable
 * @param failureReason    normalized reason on failure
 * @param kind             payment or refund outcome
 * @param refundStatus     normalized refund outcome when kind is REFUND
 * @param gatewayRefundId  gateway refund id, when present
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

    /** Payment event with no refund fields. */
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

    /** True when the event carries an outcome we act on; others are stored but IGNORED. */
    public boolean isActionable() {
        return kind == GatewayEventKind.PAYMENT && status != null;
    }

    /** True when this event describes a refund. */
    public boolean isRefund() {
        return kind == GatewayEventKind.REFUND;
    }
}
