package com.gr74.payment.gateway;

import com.gr74.payment.model.PaymentAttemptStatus;

/**
 * Gateway status answer, normalized for reconciliation.
 *
 * @param status           already mapped to our enum
 * @param gatewayPaymentId gateway transaction id, when it has one
 * @param failureReason    normalized reason on FAILED
 */
public record GatewayPaymentStatus(
        PaymentAttemptStatus status,
        String gatewayPaymentId,
        String failureReason) {
}
