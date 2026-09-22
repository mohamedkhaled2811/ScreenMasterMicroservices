package com.gr74.booking.messaging;

import java.time.Instant;

/**
 * Raised when money arrives for a booking that can no longer confirm.
 * Carries the payment id so Payment can refund without a lookup.
 */
public record BookingConfirmationRejected(
        long eventId,
        long bookingId,
        String bookingReference,
        long paymentId,
        ConfirmationRejectionReason reason,
        String userId,
        Instant occurredAt) {
}