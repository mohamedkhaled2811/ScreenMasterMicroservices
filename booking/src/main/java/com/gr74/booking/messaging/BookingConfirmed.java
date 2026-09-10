package com.gr74.booking.messaging;

import java.time.Instant;

/**
 * A booking's hold converted into a sale — raised in-JVM by
 * {@link com.gr74.booking.service.BookingConfirmer} and published to the broker by
 * {@link BookingEventPublisher} on {@code booking-confirmed-key}.
 *
 * <p>{@code userId} rides along because a Phase-4 Notification needs it and must not call back
 * for it; {@code eventId} is a random UUID (Booking has no outbox yet — Phase 4 replaces the
 * transport and mints stable ids there).
 *
 * @param eventId          random UUID for this publish (contrast Payment's outbox row id)
 * @param bookingId        which booking confirmed
 * @param bookingReference the human-facing handle, for the email
 * @param userId           opaque Identity reference, off the booking row
 * @param occurredAt       when the confirm committed (Booking's clock)
 */
public record BookingConfirmed(
        String eventId,
        long bookingId,
        String bookingReference,
        String userId,
        Instant occurredAt) {
}
