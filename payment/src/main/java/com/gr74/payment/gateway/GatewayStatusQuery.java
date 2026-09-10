package com.gr74.payment.gateway;

/**
 * What reconciliation knows when it asks a gateway "what do you think happened?".
 *
 * <p>Carries <b>both</b> ids because no single one works for every gateway: Stripe's status API is
 * keyed by the checkout-session id, Paymob's by the transaction id. The pre-3.6 single-param
 * {@code fetchStatus(String)} forced that choice onto the caller — gateway-specific
 * {@code if/else} outside the adapters, which the registry rule forbids. Now each adapter picks
 * the id it needs from this record, and the caller never asks which gateway it is talking to.
 *
 * @param gatewaySessionId the checkout-session/order id we stored at session creation; null when
 *                         the gateway never answered (reconciliation only sweeps attempts that
 *                         have one, but the type stays honest)
 * @param gatewayPaymentId the gateway's transaction id, when a webhook or earlier probe reported
 *                         one; null when nothing has ever named it
 */
public record GatewayStatusQuery(String gatewaySessionId, String gatewayPaymentId) {

    /** The common case: an attempt that opened a session and may or may not have been paid. */
    public static GatewayStatusQuery of(String gatewaySessionId, String gatewayPaymentId) {
        return new GatewayStatusQuery(gatewaySessionId, gatewayPaymentId);
    }
}
