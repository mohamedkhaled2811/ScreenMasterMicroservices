package com.gr74.payment.dto;

import java.time.Instant;

import com.gr74.payment.model.PaymentAttempt;
import com.gr74.payment.model.PaymentAttemptStatus;
import com.gr74.payment.model.PaymentGatewayType;

/**
 * One attempt, as seen from outside.
 *
 * <p>The gateway's own session and payment ids are deliberately <b>omitted</b>: they are operational
 * detail, and echoing a live session id to a client invites someone to poke at it. The checkout URL
 * is included only while the attempt is still usable.
 */
public record PaymentAttemptResponse(
        Long id,
        PaymentGatewayType gateway,
        PaymentAttemptStatus status,
        String failureReason,
        String checkoutUrl,
        Instant expiresAt,
        Instant createdAt) {

    public static PaymentAttemptResponse of(PaymentAttempt attempt) {
        boolean live = attempt.getStatus() == PaymentAttemptStatus.PENDING;
        return new PaymentAttemptResponse(
                attempt.getId(),
                attempt.getGateway(),
                attempt.getStatus(),
                attempt.getFailureReason(),
                live ? attempt.getCheckoutUrl() : null,
                attempt.getExpiresAt(),
                attempt.getCreatedAt());
    }
}
