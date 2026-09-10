package com.gr74.payment.model;

/**
 * Lifecycle of one <em>attempt</em> to settle a payment through one gateway checkout session.
 *
 * <p>Every gateway spells these differently — Paymob says {@code success}, Stripe emits
 * {@code checkout.session.completed} — and normalizing into this enum is the adapter's job. Nothing
 * outside {@code com.gr74.payment.gateway} ever sees a gateway's own words.
 *
 * <p>Persisted as a {@code String} (never an ordinal).
 */
public enum PaymentAttemptStatus {

    /** A session is open at the gateway and the outcome is unknown. At most one per payment. */
    PENDING,

    /** The gateway confirmed payment. This is what promotes the parent {@link PaymentStatus#PAID}. */
    SUCCEEDED,

    /** The gateway declined (insufficient funds, 3DS failure, ...). The user may try again. */
    FAILED,

    /** The session lapsed unused — the user closed the page. Recoverable: "Pay Again". */
    EXPIRED,

    /** The user cancelled at the gateway, or we abandoned the session. */
    CANCELLED;

    /**
     * True when this attempt can no longer change state, so a late or duplicate webhook for it must
     * be ignored rather than applied. The guard that makes out-of-order delivery safe.
     */
    public boolean isTerminal() {
        return this != PENDING;
    }
}
