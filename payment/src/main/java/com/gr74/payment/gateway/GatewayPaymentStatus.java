package com.gr74.payment.gateway;

import com.gr74.payment.model.PaymentAttemptStatus;

/**
 * A gateway's view of one payment, normalized — the answer to "what do you think happened?".
 *
 * <p>Returned by {@code fetchStatus()} and used by reconciliation, which is what makes the webhook
 * path <em>recoverable</em> rather than critical: if we were down when a webhook fired, polling this
 * is how we find out.
 *
 * @param status           already mapped into our enum by the adapter — never the gateway's word
 * @param gatewayPaymentId the gateway's transaction id, when it has one
 * @param failureReason    normalized reason when the status is {@link PaymentAttemptStatus#FAILED}
 */
public record GatewayPaymentStatus(
        PaymentAttemptStatus status,
        String gatewayPaymentId,
        String failureReason) {
}
