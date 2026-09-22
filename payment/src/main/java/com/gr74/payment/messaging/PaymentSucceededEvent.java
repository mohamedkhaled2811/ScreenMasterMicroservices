package com.gr74.payment.messaging;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * A payment reached PAID; Booking confirms off this.
 */
public record PaymentSucceededEvent(
        long eventId,
        long paymentId,
        long bookingId,
        long attemptId,
        String gateway,
        BigDecimal amount,
        String currency,
        String userId,
        Instant occurredAt) {
}
