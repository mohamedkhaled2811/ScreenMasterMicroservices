package com.gr74.payment.gateway;

import com.gr74.payment.model.RefundStatus;

/**
 * Normalized refund answer. PENDING is normal; only webhook confirmation counts toward the total.
 */
public record RefundResult(RefundStatus status, String gatewayRefundId, String failureReason) {
}
