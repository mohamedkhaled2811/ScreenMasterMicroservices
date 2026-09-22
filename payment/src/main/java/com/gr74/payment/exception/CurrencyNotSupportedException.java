package com.gr74.payment.exception;

import java.util.Set;

import com.gr74.payment.model.PaymentGatewayType;

/**
 * The chosen gateway cannot settle this booking's currency; the message names alternatives.
 */
public class CurrencyNotSupportedException extends PaymentException {

    public CurrencyNotSupportedException(PaymentGatewayType gateway, String currency,
            Set<PaymentGatewayType> alternatives) {
        super(PaymentErrorCode.PAYMENT_CURRENCY_NOT_SUPPORTED,
                "Gateway " + gateway + " cannot settle " + currency
                        + "; gateways that can: " + alternatives);
    }
}
