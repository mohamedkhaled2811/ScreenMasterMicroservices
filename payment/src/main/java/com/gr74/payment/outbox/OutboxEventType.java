package com.gr74.payment.outbox;

/**
 * The business facts this service can announce through the outbox.
 *
 * <p>Kept as an enum rather than a free string so a typo'd event type fails at compile time, not
 * as a silent row no consumer ever binds to. Adding an event is a new constant plus its routing
 * key — existing rows never change.
 */
public enum OutboxEventType {

    /** A payment reached PAID. Booking confirms off this. */
    PAYMENT_SUCCEEDED,

    /** An attempt failed terminally. Booking mirrors it; the user may retry until the hold lapses. */
    PAYMENT_FAILED
}
