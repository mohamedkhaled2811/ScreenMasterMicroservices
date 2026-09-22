package com.gr74.payment.gateway;

import com.gr74.payment.exception.PaymentErrorCode;
import com.gr74.payment.exception.PaymentException;
import com.gr74.payment.model.PaymentGatewayType;

/**
 * Infrastructural gateway failure (unreachable, timeout, 5xx, malformed response).
 * A decline is a successful call returning FAILED, never this. Maps to 503; attempt stays PENDING.
 */
public class GatewayException extends PaymentException {

    public GatewayException(PaymentGatewayType gateway, String message, Throwable cause) {
        super(PaymentErrorCode.PAYMENT_GATEWAY_UNAVAILABLE,
                "Gateway " + gateway + " failed: " + message, cause);
    }

    public GatewayException(PaymentGatewayType gateway, String message) {
        super(PaymentErrorCode.PAYMENT_GATEWAY_UNAVAILABLE,
                "Gateway " + gateway + " failed: " + message);
    }
}
