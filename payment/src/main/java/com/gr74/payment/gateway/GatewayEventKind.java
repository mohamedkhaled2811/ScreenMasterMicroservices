package com.gr74.payment.gateway;

/**
 * Whether a webhook carries a payment or a refund outcome; routes to the matching ledger.
 */
public enum GatewayEventKind {

    /** Payment outcome; settles an attempt and possibly its payment. */
    PAYMENT,

    /** Refund outcome; confirms or rejects a refund row. */
    REFUND
}
