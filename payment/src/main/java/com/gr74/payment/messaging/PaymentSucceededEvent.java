package com.gr74.payment.messaging;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * A payment reached {@code PAID} — the fact Booking confirms off.
 *
 * <p>Duplicated per consuming service rather than shared via a jar (the {@code MovieUpserted}
 * convention): each side owns its copy of the wire shape, so neither service's deploy can break
 * the other's compile. A DTO on the wire, never an entity — and it carries the ids Booking needs
 * to confirm without calling back ({@code paymentId} for the confirmation, {@code bookingId} for
 * the lookup, {@code userId} for the event Phase 4 needs).
 *
 * @param eventId    the outbox row id — stable across redeliveries, so the consumer can dedupe
 * @param paymentId  which obligation settled
 * @param bookingId  which booking may now confirm
 * @param attemptId  which try settled it (forensics, not decisions)
 * @param gateway    which gateway moved the money
 * @param amount     settled amount, snapshotted from the payment
 * @param currency   ISO-4217 code travelling with the amount, as always
 * @param userId     opaque Identity reference, off the payment row
 * @param occurredAt when the payment went PAID
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
