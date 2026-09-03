package com.gr74.payment.model;

/**
 * Lifecycle of a payment <em>obligation</em> — "does booking #1001 still owe money?".
 *
 * <p>Deliberately distinct from {@link PaymentAttemptStatus}: a payment can have three failed
 * attempts and still be {@code PENDING}, because the obligation outlives any single try. It is also
 * distinct from Booking's {@code BookingStatus} — Booking never interprets a gateway's vocabulary,
 * it only reacts to the business-level fact that a payment succeeded.
 *
 * <p>Only two things promote a payment: a terminal {@link PaymentAttemptStatus#SUCCEEDED} attempt
 * ({@code -> PAID}), and refunds accumulating against it ({@code -> PARTIALLY_REFUNDED / REFUNDED}).
 *
 * <p>Persisted as a {@code String} (never an ordinal — see the project convention and
 * {@code docs/concepts/jpa-and-hibernate.md}).
 */
public enum PaymentStatus {

    /** Owed, not yet settled. The starting state, and where a payment sits between attempts. */
    PENDING,

    /** Settled in full. Written only by a verified webhook or reconciliation — never by the client. */
    PAID,

    /** Terminally unpayable (e.g. the booking expired before any attempt succeeded). */
    FAILED,

    /** Abandoned deliberately, e.g. the user cancelled the booking. */
    CANCELLED,

    /** Fully refunded: the sum of SUCCEEDED refunds equals the amount. */
    REFUNDED,

    /** Partially refunded: some money returned, a positive remainder still refundable. */
    PARTIALLY_REFUNDED;

    /** True when no further attempt may be created — the obligation is closed. */
    public boolean isTerminal() {
        return this != PENDING;
    }
}
