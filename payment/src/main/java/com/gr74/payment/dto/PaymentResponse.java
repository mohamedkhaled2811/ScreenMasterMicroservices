package com.gr74.payment.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import com.gr74.payment.model.Payment;
import com.gr74.payment.model.PaymentStatus;

/**
 * A payment with its attempt history, oldest first.
 */
public record PaymentResponse(
        Long id,
        Long bookingId,
        PaymentStatus status,
        BigDecimal amount,
        String currency,
        BigDecimal refundedAmount,
        BigDecimal remainingRefundable,
        Instant createdAt,
        Instant updatedAt,
        List<PaymentAttemptResponse> attempts) {

    public static PaymentResponse of(Payment payment, List<PaymentAttemptResponse> attempts) {
        return new PaymentResponse(
                payment.getId(),
                payment.getBookingId(),
                payment.getStatus(),
                payment.getAmount(),
                payment.getCurrency(),
                payment.getRefundedAmount(),
                payment.remainingRefundable(),
                payment.getCreatedAt(),
                payment.getUpdatedAt(),
                attempts);
    }
}
