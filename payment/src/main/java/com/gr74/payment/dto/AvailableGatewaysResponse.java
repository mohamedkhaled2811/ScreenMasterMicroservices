package com.gr74.payment.dto;

import java.util.Set;

import com.gr74.payment.model.PaymentGatewayType;

/**
 * Gateways usable in this deployment, optionally filtered by currency.
 */
public record AvailableGatewaysResponse(String currency, Set<PaymentGatewayType> gateways) {
}
