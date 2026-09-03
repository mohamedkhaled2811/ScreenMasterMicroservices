package com.gr74.payment.dto;

import java.util.Set;

import com.gr74.payment.model.PaymentGatewayType;

/**
 * What {@code GET /payments/gateways} returns: which gateways this deployment can actually use.
 *
 * <p>When a currency is supplied the list is filtered to gateways that can settle it, so a client's
 * picker never offers Paymob for a USD booking. Filtering here — rather than letting the user find
 * out at checkout — is the visible half of the multi-currency decision.
 */
public record AvailableGatewaysResponse(String currency, Set<PaymentGatewayType> gateways) {
}
