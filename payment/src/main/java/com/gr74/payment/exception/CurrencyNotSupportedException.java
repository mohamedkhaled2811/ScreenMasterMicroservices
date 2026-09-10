package com.gr74.payment.exception;

import java.util.Set;

import com.gr74.payment.model.PaymentGatewayType;

/**
 * The chosen gateway is registered but cannot settle this booking's currency — e.g. Paymob asked to
 * take USD, or Stripe test mode asked to take EGP.
 *
 * <p>Caught here rather than at the gateway so the user finds out while choosing, not after being
 * redirected. The message names the gateways that <em>can</em> take this currency.
 */
public class CurrencyNotSupportedException extends PaymentException {

    public CurrencyNotSupportedException(PaymentGatewayType gateway, String currency,
            Set<PaymentGatewayType> alternatives) {
        super(PaymentErrorCode.PAYMENT_CURRENCY_NOT_SUPPORTED,
                "Gateway " + gateway + " cannot settle " + currency
                        + "; gateways that can: " + alternatives);
    }
}
