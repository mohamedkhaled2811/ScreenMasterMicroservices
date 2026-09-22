package com.gr74.payment.dto;

import java.math.BigDecimal;
import java.time.Instant;

import com.gr74.payment.model.Refund;
import com.gr74.payment.model.RefundStatus;

/**
 * One refund row; PENDING until the gateway webhook confirms it.
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
