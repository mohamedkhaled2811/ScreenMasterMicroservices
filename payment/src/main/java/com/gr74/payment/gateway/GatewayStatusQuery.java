package com.gr74.payment.gateway;

/**
 * Reconciliation status query. Carries both ids; each adapter reads the one its API keys on.
 *
 * @param gatewaySessionId checkout-session id, null when the gateway never answered
 * @param gatewayPaymentId gateway transaction id, null when never reported
 */
public record GatewayStatusQuery(String gatewaySessionId, String gatewayPaymentId) {

    /** Status query for an attempt with a session. */
    public static GatewayStatusQuery of(String gatewaySessionId, String gatewayPaymentId) {
        return new GatewayStatusQuery(gatewaySessionId, gatewayPaymentId);
    }
}
