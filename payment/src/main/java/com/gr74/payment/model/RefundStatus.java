package com.gr74.payment.model;

/**
 * Lifecycle of a single refund.
 *
 * <p>A refund is {@link #SUCCEEDED} only when the gateway's own refund webhook confirms it — not
 * when the refund API call returns. Until then the money has not demonstrably moved, so only
 * {@code SUCCEEDED} refunds count toward {@code payments.refunded_amount}.
 *
 * <p>Persisted as a {@code String} (never an ordinal).
 */
public enum RefundStatus {

    /** Requested at the gateway; awaiting confirmation. */
    PENDING,

    /** Confirmed by the gateway. Counts toward the payment's refunded total. */
    SUCCEEDED,

    /** The gateway rejected it. Does not count toward the total; may be retried under a new key. */
    FAILED
}
