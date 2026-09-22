package com.gr74.booking.messaging;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Booking's local copy of Payment's {@code PaymentSucceeded} event, matched by field name during JSON deserialization.
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
