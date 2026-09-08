package com.gr74.booking.messaging;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Booking's local copy of the {@code PaymentSucceeded} event Payment's outbox relay publishes.
 *
 * <p>Deliberately duplicated rather than shared (the {@code MovieUpsertedEvent} convention): the
 * two services share no jar, so each side owns its copy of the wire shape and fields are matched
 * by name during JSON deserialization. The field shape must match what Payment's outbox actually
 * publishes — {@code eventId} is injected by the relay (the outbox row id, stable across
 * redeliveries), the rest is snapshotted from the payment row at apply time.
 *
 * @param eventId    the outbox row id — stable across redeliveries
 * @param paymentId  which obligation settled (carried into a rejection so Payment can refund
 *                   without a lookup)
 * @param bookingId  which booking may now confirm — the confirm's lookup key
 * @param attemptId  which try settled it (forensics, not decisions)
 * @param gateway    which gateway moved the money
 * @param amount     settled amount, snapshotted from the payment
 * @param currency   ISO-4217 code travelling with the amount, as always
 * @param userId     opaque Identity reference, off the payment row
 * @param occurredAt when the payment went PAID (Payment's clock)
 */
public record PaymentSucceededEvent(
        long eventId,
        long paymentId,
        long bookingId,
        long attemptId,
        String gateway,
        BigDecimal amount,
        String currency,
        String userId,
        Instant occurredAt) {
}
