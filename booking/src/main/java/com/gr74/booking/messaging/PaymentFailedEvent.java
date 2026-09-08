package com.gr74.booking.messaging;

import java.time.Instant;

/**
 * Booking's local copy of the {@code PaymentFailed} event Payment's outbox relay publishes.
 *
 * <p>Same duplication convention as {@link PaymentSucceededEvent}: Booking owns its copy of the
 * wire shape. Booking <b>mirrors</b> this onto its read-model field only — seats stay held and the
 * user may retry until the hold lapses. Nothing is ever decided from it.
 *
 * @param eventId       the outbox row id — stable across redeliveries
 * @param paymentId     which obligation the failed try belonged to
 * @param bookingId     which booking the user may retry for — the mirror's lookup key
 * @param attemptId     which try failed
 * @param gateway       which gateway declined
 * @param failureReason normalized reason, never the gateway's raw vocabulary
 * @param occurredAt    when the failure was applied (Payment's clock)
 */
public record PaymentFailedEvent(
        long eventId,
        long paymentId,
        long bookingId,
        long attemptId,
        String gateway,
        String failureReason,
        Instant occurredAt) {
}
