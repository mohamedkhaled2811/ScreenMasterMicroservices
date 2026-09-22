package com.gr74.booking.messaging;

import java.time.Instant;

/**
 * Booking's local copy of Payment's {@code PaymentFailed} event, mirrored onto the read-model field only.
 */
public record PaymentFailedEvent(
        long eventId,
        long paymentId,
        long bookingId,
        long attemptId,
        String gateway,
        String failureReason,
        Instant occurredAt) {
}
