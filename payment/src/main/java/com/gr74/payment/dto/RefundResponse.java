package com.gr74.payment.dto;

import java.math.BigDecimal;
import java.time.Instant;

import com.gr74.payment.model.Refund;
import com.gr74.payment.model.RefundStatus;

/**
 * One refund row — what {@code POST /payments/{id}/refunds} returns.
 *
 * <p>A {@code PENDING} status means the gateway accepted the request and the money has NOT
 * demonstrably moved yet: only the refund webhook promotes it to {@code SUCCEEDED}. Poll the
 * payment ({@code refundedAmount} accumulates only confirmed refunds) rather than treating this
 * response as proof.
 */
public record RefundResponse(
        Long id,
        Long paymentId,
        BigDecimal amount,
        String currency,
        RefundStatus status,
        String reason,
        String gatewayRefundId,
        String idempotencyKey,
        Instant createdAt) {

    public static RefundResponse of(Refund refund, String currency) {
        return new RefundResponse(
                refund.getId(),
                refund.getPayment().getId(),
                refund.getAmount(),
                currency,
                refund.getStatus(),
                refund.getReason(),
                refund.getGatewayRefundId(),
                refund.getIdempotencyKey(),
                refund.getCreatedAt());
    }
}
