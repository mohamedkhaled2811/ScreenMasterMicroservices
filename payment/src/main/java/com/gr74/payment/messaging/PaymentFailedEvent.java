package com.gr74.payment.messaging;

import java.time.Instant;

/**
 * An attempt failed terminally at the gateway.
 *
 * <p>Booking <b>mirrors</b> this onto its read-model field only — seats stay held and the user may
 * retry until the hold lapses. Nothing is ever decided from it: a payment with three failed
 * attempts is still {@code PENDING} and payable. Same duplication convention as
 * {@link PaymentSucceededEvent}: each side owns its copy of the wire shape.
 *
 * @param eventId       the outbox row id — stable across redeliveries
 * @param paymentId     which obligation the failed try belonged to
 * @param bookingId     which booking the user may retry for
 * @param attemptId     which try failed
 * @param gateway       which gateway declined
 * @param failureReason normalized reason, never the gateway's raw vocabulary
 * @param occurredAt    when the failure was applied
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
