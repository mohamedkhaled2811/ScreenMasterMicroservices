package com.gr74.payment.exception;

import java.util.Set;

import com.gr74.payment.model.PaymentGatewayType;

/**
 * The requested gateway is not registered here (400); the message lists what is available.
 */
public class GatewayNotAvailableException extends PaymentException {

    public GatewayNotAvailableException(PaymentGatewayType requested, Set<PaymentGatewayType> available) {
        super(PaymentErrorCode.PAYMENT_GATEWAY_NOT_AVAILABLE,
                "Gateway " + requested + " is not available; configured gateways: " + available);
    }
}
