package com.gr74.notification.messaging;

import java.time.Instant;

/**
 * A booking's hold converted into a sale — Notification's OWN copy of Booking's
 * {@code BookingConfirmed} wire shape (cross-service types are duplicated by design: no shared jar,
 * so neither service's deploy can break the other's compile).
 *
 * @param eventId          the publisher's outbox row id — STABLE across redeliveries, which is what
 *                         makes consumer dedupe possible (the {@code processed_events} PK)
 * @param bookingId        which booking confirmed
 * @param bookingReference the human-facing handle, for the email
 * @param userId           opaque Identity reference, off the booking row
 * @param occurredAt       when the confirm committed (Booking's clock)
 */
public record BookingConfirmedEvent(
        long eventId,
        long bookingId,
        String bookingReference,
        String userId,
        Instant occurredAt) {
}