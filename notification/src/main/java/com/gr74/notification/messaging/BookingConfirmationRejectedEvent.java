package com.gr74.notification.messaging;

import java.time.Instant;

/**
 * Money arrived for a booking that can no longer confirm — Notification's OWN copy of Booking's
 * {@code BookingConfirmationRejected} wire shape (cross-service types are duplicated by design: no
 * shared jar, so neither service's deploy can break the other's compile).
 *
 * <p>This is the compensation trigger: the customer must be told the money is coming back. It
 * carries <b>both</b> the {@code reason} and the {@code paymentId} so the notification can name them
 * without calling back.
 *
 * @param eventId          the publisher's outbox row id — STABLE across redeliveries, the dedupe key
 * @param bookingId        which booking could not confirm
 * @param bookingReference the human-facing handle, for the email
 * @param paymentId        which obligation is being refunded — from the triggering event, not a lookup
 * @param reason           why the confirm failed (EXPIRED | CANCELLED)
 * @param userId           opaque Identity reference, off the booking row
 * @param occurredAt       when the rejection was decided (Booking's clock)
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