package com.gr74.payment.model;

/**
 * Lifecycle of a payment obligation. Distinct from attempt status; stored as STRING.
 */
public enum PaymentStatus {

    /** Owed, not yet settled. */
    PENDING,

    /** Settled in full; written only by verified webhook or reconciliation. */
    PAID,

    /** Unpayable (e.g. booking expired before any attempt succeeded). */
    FAILED,

    /** Abandoned deliberately. */
    CANCELLED,

    /** Fully refunded. */
    REFUNDED,

    /** Partially refunded. */
    PARTIALLY_REFUNDED;

    /** True when the obligation is closed and no further attempt may be created. */
    public boolean isTerminal() {
        return this != PENDING;
    }
}
