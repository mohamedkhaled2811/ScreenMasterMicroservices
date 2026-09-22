package com.gr74.payment.dto;

import java.math.BigDecimal;
import java.time.Instant;

import com.gr74.payment.model.Payment;
import com.gr74.payment.model.PaymentAttempt;
import com.gr74.payment.model.PaymentAttemptStatus;
import com.gr74.payment.model.PaymentGatewayType;
import com.gr74.payment.model.PaymentStatus;

/**
 * {@code POST /payments} response: checkout URL and its expiry (the gateway session deadline).
 */
public record PaymentSessionResponse(
        Long paymentId,
        Long attemptId,
        Long bookingId,
        PaymentStatus paymentStatus,
        PaymentAttemptStatus attemptStatus,
        PaymentGatewayType gateway,
        String checkoutUrl,
        Instant expiresAt,
        BigDecimal amount,
        String currency) {

    public static PaymentSessionResponse of(Payment payment, PaymentAttempt attempt) {
        return new PaymentSessionResponse(
                payment.getId(),
                attempt.getId(),
                payment.getBookingId(),
                payment.getStatus(),
                attempt.getStatus(),
                attempt.getGateway(),
                attempt.getCheckoutUrl(),
                attempt.getExpiresAt(),
                payment.getAmount(),
                payment.getCurrency());
    }
}
