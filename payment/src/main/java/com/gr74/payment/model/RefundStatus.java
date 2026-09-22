package com.gr74.payment.model;

/**
 * Lifecycle of a single refund. Only SUCCEEDED counts toward the refunded total; stored as STRING.
 */
public enum RefundStatus {

    /** Requested; awaiting webhook confirmation. */
    PENDING,

    /** Confirmed by webhook; counts toward the refunded total. */
    SUCCEEDED,

    /** Rejected; may be retried under a new key. */
    FAILED
}
