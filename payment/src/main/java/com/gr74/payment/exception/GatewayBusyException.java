package com.gr74.payment.exception;

import com.gr74.payment.model.PaymentGatewayType;

/**
 * The gateway bulkhead is full; the call was rejected without queueing (429).
 */
public class GatewayBusyException extends PaymentException {

    public GatewayBusyException(PaymentGatewayType gateway, String message, Throwable cause) {
        super(PaymentErrorCode.PAYMENT_GATEWAY_BUSY,
                "Gateway " + gateway + " is at capacity: " + message, cause);
    }

    public GatewayBusyException(PaymentGatewayType gateway, String message) {
        super(PaymentErrorCode.PAYMENT_GATEWAY_BUSY,
                "Gateway " + gateway + " is at capacity: " + message);
    }
}