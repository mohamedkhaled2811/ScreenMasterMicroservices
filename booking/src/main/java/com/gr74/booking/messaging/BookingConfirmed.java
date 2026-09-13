package com.gr74.booking.messaging;

import java.time.Instant;

/**
 * A booking's hold converted into a sale — raised in-JVM by
 * {@link com.gr74.booking.service.BookingConfirmer}, written to the outbox in the same transaction,
 * and published to the broker by the outbox relay on {@code booking-confirmed-key}.
 *
 * <p>{@code userId} rides along because a Phase-4 Notification needs it and must not call back
 * for it; {@code eventId} is the <b>outbox row id</b>, injected by the relay at publish time —
 * stable across redeliveries, which is what makes the consumer's dedupe fire at all (a fresh id
 * per publish would make every redelivery look like a new event).
 *
 * @param eventId          the outbox row id — stable across redeliveries (contrast the old random UUID)
 * @param bookingId        which booking confirmed
 * @param bookingReference the human-facing handle, for the email
 * @param userId           opaque Identity reference, off the booking row
 * @param occurredAt       when the confirm committed (Booking's clock)
 */
public record BookingConfirmed(
        long eventId,
        long bookingId,
        String bookingReference,
        String userId,
        Instant occurredAt) {
}