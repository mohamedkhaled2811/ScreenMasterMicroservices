package com.gr74.payment.messaging;

import java.time.Instant;

/**
 * Money arrived for a booking that can no longer confirm — Payment's copy of the event Booking
 * publishes on {@code booking-confirmation-rejected-key}.
 *
 * <p>Duplicated as a record per the {@code MovieUpsertedEvent} convention: no shared jar across
 * the broker boundary, only a shared contract (field names and the routing key). Carries the
 * {@code paymentId} from the triggering event so the compensation needs no lookup, and the
 * {@code eventId} so the auto-refund's derived idempotency key ({@code "reject-" + eventId})
 * makes redelivery collapse onto one refund row.
 *
 * @param eventId          Booking's publish id (a UUID — contrast Payment's outbox row ids)
 * @param bookingId        which booking could not confirm
 * @param bookingReference the human-facing handle, for the audit trail
 * @param paymentId        which obligation to refund in full
 * @param reason           why the confirm failed ({@code EXPIRED} | {@code CANCELLED})
 * @param userId           opaque Identity reference, off the booking row
 * @param occurredAt       when the rejection was decided (Booking's clock)
 */
public record BookingConfirmationRejectedEvent(
        String eventId,
        long bookingId,
        String bookingReference,
        long paymentId,
        String reason,
        String userId,
        Instant occurredAt) {

    /** The derived idempotency key that makes redelivery of this rejection safe. */
    public String refundIdempotencyKey() {
        return "reject-" + eventId;
    }

    /** The refund reason for the audit trail — the booking-side cause, in our vocabulary. */
    public String refundReason() {
        return "CANCELLED".equals(reason) ? "BOOKING_CANCELLED" : "BOOKING_EXPIRED";
    }
}
