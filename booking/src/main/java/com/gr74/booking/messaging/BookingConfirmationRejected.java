package com.gr74.booking.messaging;

import java.time.Instant;

/**
 * Money arrived for a booking that can no longer confirm — raised in-JVM by
 * {@link com.gr74.booking.service.BookingConfirmer}, written to the outbox in the same transaction,
 * and published to the broker by the outbox relay on {@code booking-confirmation-rejected-key}.
 *
 * <p>This is the compensation trigger step 3.5 consumes: it carries <b>both</b> the {@code reason}
 * and the {@code paymentId} from the event, so Payment can auto-refund without a lookup. Losing
 * this event strands a customer's refund — the exposure Booking carried through Phase 3, now closed
 * by the outbox (the row and the rejection commit together, and the relay retries until the broker
 * accepts). {@code eventId} is the <b>outbox row id</b>, stable across redeliveries.
 *
 * @param eventId          the outbox row id — stable across redeliveries (contrast the old random UUID)
 * @param bookingId        which booking could not confirm
 * @param bookingReference the human-facing handle, for the audit trail
 * @param paymentId        which obligation to refund — from the triggering event, not a lookup
 * @param reason           why the confirm failed (EXPIRED | CANCELLED)
 * @param userId           opaque Identity reference, off the booking row
 * @param occurredAt       when the rejection was decided (Booking's clock)
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