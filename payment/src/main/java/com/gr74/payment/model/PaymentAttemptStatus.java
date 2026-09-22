package com.gr74.payment.model;

/**
 * Lifecycle of one attempt. Adapters normalize gateway vocabulary into this enum; stored as STRING.
 */
public enum PaymentAttemptStatus {

    /** Session open, outcome unknown. At most one per payment. */
    PENDING,

    /** Gateway confirmed payment; promotes the parent payment to PAID. */
    SUCCEEDED,

    /** Gateway declined; user may try again. */
    FAILED,

    /** Session lapsed unused; recoverable via Pay Again. */
    EXPIRED,

    /** Cancelled at the gateway or abandoned. */
    CANCELLED;

    /** True when the attempt can no longer change state; late webhooks are ignored. */
    public boolean isTerminal() {
        return this != PENDING;
    }
}
