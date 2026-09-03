package com.gr74.payment.exception;

import java.util.Set;

import com.gr74.payment.model.PaymentGatewayType;

/**
 * A client asked for a gateway this deployment has not registered — its credentials are absent, so
 * the adapter was conditioned out of the context entirely.
 *
 * <p>A 400, not a 503: nothing is broken, the request simply names something that does not exist
 * here. The message lists what <em>is</em> available so the caller can correct itself.
 */
public class GatewayNotAvailableException extends PaymentException {

    public GatewayNotAvailableException(PaymentGatewayType requested, Set<PaymentGatewayType> available) {
        super(PaymentErrorCode.PAYMENT_GATEWAY_NOT_AVAILABLE,
                "Gateway " + requested + " is not available; configured gateways: " + available);
    }
}
