package com.gr74.payment.gateway;

import com.gr74.payment.model.RefundStatus;

/**
 * What a gateway returned from a refund request, normalized.
 *
 * <p>Note that {@link RefundStatus#PENDING} is a perfectly normal answer: most gateways accept a
 * refund and confirm it asynchronously by webhook. Only that confirmation counts toward a payment's
 * refunded total — an accepted request has not yet moved money.
 */
public record RefundResult(RefundStatus status, String gatewayRefundId, String failureReason) {
}
