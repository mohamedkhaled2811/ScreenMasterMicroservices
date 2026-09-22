package com.gr74.payment.outbox;

/**
 * Business facts this service can announce through the outbox.
 */
public enum OutboxEventType {

    /** A payment reached PAID. */
    PAYMENT_SUCCEEDED,

    /** An attempt failed terminally. */
    PAYMENT_FAILED
}
