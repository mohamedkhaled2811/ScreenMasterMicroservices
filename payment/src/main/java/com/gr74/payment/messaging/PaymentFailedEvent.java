package com.gr74.payment.messaging;

import java.time.Instant;

/**
 * An attempt failed terminally; Booking mirrors it and the user may retry.
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
