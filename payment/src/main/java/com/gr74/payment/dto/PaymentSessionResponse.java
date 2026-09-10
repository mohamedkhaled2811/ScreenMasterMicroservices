package com.gr74.payment.dto;

import java.math.BigDecimal;
import java.time.Instant;

import com.gr74.payment.model.Payment;
import com.gr74.payment.model.PaymentAttempt;
import com.gr74.payment.model.PaymentAttemptStatus;
import com.gr74.payment.model.PaymentGatewayType;
import com.gr74.payment.model.PaymentStatus;

/**
 * What {@code POST /payments} returns: where to send the user, and when that link goes stale.
 *
 * <p>{@code expiresAt} is the <b>gateway session</b> deadline, not the booking's. A client showing a
 * countdown should show the booking's hold, not this — a lapsed session just means "ask for a new
 * link", while a lapsed booking means "start over".
 *
 * <p>A DTO, not the entity: {@link Payment} carries a user id and internal state the wire has no
 * business seeing.
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
