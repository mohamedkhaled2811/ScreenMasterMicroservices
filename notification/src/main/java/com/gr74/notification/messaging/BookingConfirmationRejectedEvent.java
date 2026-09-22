package com.gr74.notification.messaging;

import java.time.Instant;

/**
 * Late payment for a booking that can no longer confirm; triggers the refund notice.
 *
 * @param eventId          dedupe key, stable across redeliveries
 * @param bookingId        which booking could not confirm
 * @param bookingReference the human-facing handle, for the email
 * @param paymentId        which obligation is being refunded
 * @param reason           why the confirm failed (EXPIRED | CANCELLED)
 * @param userId           Identity reference, off the booking row
 * @param occurredAt       when the rejection was decided
 */
public record BookingConfirmationRejectedEvent(
        long eventId,
        long bookingId,
        String bookingReference,
        long paymentId,
        ConfirmationRejectionReason reason,
        String userId,
        Instant occurredAt) {
}