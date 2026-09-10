package com.gr74.payment.gateway;

/**
 * What kind of outcome a verified webhook carries.
 *
 * <p>A gateway emits two vocabularies over the same webhook pipe: payment outcomes (did the charge
 * settle?) and refund outcomes (did money go back?). Both share the evidence store, the
 * {@code UNIQUE (gateway, event_id)} dedupe, and the 200-to-almost-everything rule — but they route
 * to different ledgers: payment events to {@code PaymentWriter.applyOutcome}, refund events to the
 * refund ledger (step 3.5). This enum is the routing bit.
 */
public enum GatewayEventKind {

    /** A payment outcome — settles an attempt and possibly its payment. */
    PAYMENT,

    /** A refund outcome — confirms or rejects a refund row. Provisional until 3.5 wires the ledger. */
    REFUND
}
