package com.gr74.payment.model;

/**
 * What happened to a stored webhook delivery after acceptance; stored as STRING.
 */
public enum WebhookProcessingStatus {

    /** Stored, not yet processed. */
    RECEIVED,

    /** Applied: changed an attempt and its payment. */
    PROCESSED,

    /** Not applied (unknown type, unknown session, or terminal attempt); still answered 200. */
    IGNORED,

    /** Processing threw; row is the evidence for replay. */
    FAILED
}
