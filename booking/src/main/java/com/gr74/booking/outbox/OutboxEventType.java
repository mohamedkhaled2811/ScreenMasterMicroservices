package com.gr74.booking.outbox;

/**
 * The business facts this service can announce through the outbox.
 *
 * <p>Kept as an enum rather than a free string so a typo'd event type fails at compile time, not
 * as a silent row no consumer ever binds to. Adding an event is a new constant plus its routing
 * key — existing rows never change. Mirrors {@code payment}'s {@code OutboxEventType}.
 */
public enum OutboxEventType {

    /** A hold became a sale — the confirm committed. Notification sends the booking email off this. */
    BOOKING_CONFIRMED,

    /**
     * Money arrived for a booking that can no longer confirm. This is the compensation trigger
     * 3.5's source: Payment refunds off it, and losing it strands the customer's refund — the
     * exposure the outbox exists to close.
     */
    BOOKING_CONFIRMATION_REJECTED
}