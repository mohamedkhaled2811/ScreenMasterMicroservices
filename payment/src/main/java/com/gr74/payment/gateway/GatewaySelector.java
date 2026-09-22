package com.gr74.payment.gateway;

import org.springframework.stereotype.Component;

import com.gr74.payment.exception.CurrencyNotSupportedException;
import com.gr74.payment.model.PaymentGatewayType;

import lombok.RequiredArgsConstructor;

/**
 * Picks the gateway for a payment: client chooses, selector verifies currency support.
 */
@Component
@RequiredArgsConstructor
public class GatewaySelector {

    private final GatewayRegistry registry;

    /**
     * Resolve the adapter for a requested gateway and currency.
     *
     * @throws com.gr74.payment.exception.GatewayNotAvailableException if the gateway is not registered
     * @throws CurrencyNotSupportedException                           if it cannot settle this currency
     */
    public PaymentGateway select(PaymentGatewayType requested, String currency) {
        PaymentGateway gateway = registry.require(requested); // 400 if unregistered
        if (!gateway.supportedCurrencies().contains(currency)) {
            throw new CurrencyNotSupportedException(requested, currency, registry.availableFor(currency));
        }
        return gateway;
    }
}
