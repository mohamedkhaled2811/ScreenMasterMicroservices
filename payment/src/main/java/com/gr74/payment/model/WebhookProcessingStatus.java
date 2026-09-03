package com.gr74.payment.model;

/**
 * What happened to a stored webhook delivery after we accepted it.
 *
 * <p>Kept separate from the payment statuses because it answers a different question: not "did the
 * money move?" but "what did we do with this delivery?". It is the field you scan when a payment
 * looks wrong and you need to know whether the gateway ever told us.
 *
 * <p>Persisted as a {@code String} (never an ordinal).
 */
public enum WebhookProcessingStatus {

    /** Stored, not yet processed. The state every delivery is written in, before business logic. */
    RECEIVED,

    /** Applied: an attempt and its payment changed state because of this event. */
    PROCESSED,

    /**
     * Deliberately not applied — an event type we do not act on, an unknown session id, or an
     * attempt already in a terminal state. Not an error: we still answer 200.
     */
    IGNORED,

    /** Processing threw. The row is the evidence needed to replay it once the bug is fixed. */
    FAILED
}
