package com.gr74.payment.messaging;

import java.time.Instant;

/**
 * Booking could not confirm after payment; carries what the auto-refund needs.
 */
public record BookingConfirmationRejectedEvent(
        String eventId,
        long bookingId,
        String bookingReference,
        long paymentId,
        String reason,
        String userId,
        Instant occurredAt) {

    /** Derived idempotency key making redelivery safe. */
    public String refundIdempotencyKey() {
        return "reject-" + eventId;
    }

    /** Refund reason mapped into payment vocabulary. */
    public String refundReason() {
        return "CANCELLED".equals(reason) ? "BOOKING_CANCELLED" : "BOOKING_EXPIRED";
    }
}
