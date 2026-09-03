package com.gr74.payment.gateway;

import org.springframework.stereotype.Component;

import com.gr74.payment.exception.CurrencyNotSupportedException;
import com.gr74.payment.model.PaymentGatewayType;

import lombok.RequiredArgsConstructor;

/**
 * Decides <b>which</b> gateway processes a payment.
 *
 * <p>Today the rule is short: the client chooses, and we verify that choice can actually settle the
 * booking's currency. That currency check is a real routing rule, forced by supporting both EGP and
 * USD — Paymob will not take USD, Stripe test mode will not take EGP — and it is the reason this
 * class exists rather than callers reaching into {@link GatewayRegistry} directly.
 *
 * <p>The point is the <b>seam</b>, not the rules. Routing by country, fee, gateway health, or
 * success rate would all be changes to this one bean, with no caller edits. We deliberately do not
 * build those now: an unused routing engine is speculation, an isolated decision point is design.
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
