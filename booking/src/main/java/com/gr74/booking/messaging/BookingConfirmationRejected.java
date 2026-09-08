package com.gr74.booking.messaging;

import java.time.Instant;

/**
 * Money arrived for a booking that can no longer confirm — raised in-JVM by
 * {@link com.gr74.booking.service.BookingConfirmer} and published to the broker by
 * {@link BookingEventPublisher} on {@code booking-confirmation-rejected-key}.
 *
 * <p>This is the compensation trigger step 3.5 consumes: it carries <b>both</b> the {@code reason}
 * and the {@code paymentId} from the event, so Payment can auto-refund without a lookup. Losing
 * this event strands a customer's refund (named gap — see {@link BookingEventPublisher}).
 *
 * @param eventId          random UUID for this publish (contrast Payment's outbox row id)
 * @param bookingId        which booking could not confirm
 * @param bookingReference the human-facing handle, for the audit trail
 * @param paymentId        which obligation to refund — from the triggering event, not a lookup
 * @param reason           why the confirm failed (EXPIRED | CANCELLED)
 * @param userId           opaque Identity reference, off the booking row
 * @param occurredAt       when the rejection was decided (Booking's clock)
 */
public record BookingConfirmationRejected(
        String eventId,
        long bookingId,
        String bookingReference,
        long paymentId,
        ConfirmationRejectionReason reason,
        String userId,
        Instant occurredAt) {
}
