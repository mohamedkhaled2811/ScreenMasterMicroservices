package com.gr74.payment.gateway;

import static java.util.function.Function.identity;
import static java.util.stream.Collectors.toUnmodifiableMap;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import com.gr74.payment.exception.GatewayNotAvailableException;
import com.gr74.payment.model.PaymentGatewayType;

import lombok.extern.slf4j.Slf4j;

/**
 * Dispatch from gateway type to its adapter. Built from the injected (resilience-decorated) adapter list.
 */
@Slf4j
@Component
public class GatewayRegistry {

    private final Map<PaymentGatewayType, PaymentGateway> byType;

    public GatewayRegistry(@Qualifier("resilientPaymentGateways") List<PaymentGateway> gateways) {
        this.byType = gateways.stream().collect(toUnmodifiableMap(PaymentGateway::type, identity()));
        log.info("Registered payment gateways: {}", available());
    }

    /**
     * Adapter for type.
     *
     * @throws GatewayNotAvailableException when nothing is registered for it
     */
    public PaymentGateway require(PaymentGatewayType type) {
        PaymentGateway gateway = byType.get(type);
        if (gateway == null) {
            throw new GatewayNotAvailableException(type, available());
        }
        return gateway;
    }

    /** Registered gateways, sorted. */
    public Set<PaymentGatewayType> available() {
        return new TreeSet<>(byType.keySet());
    }

    /** Gateways that can settle the currency. */
    public Set<PaymentGatewayType> availableFor(String currency) {
        Set<PaymentGatewayType> matching = new TreeSet<>(Comparator.naturalOrder());
        byType.forEach((type, gateway) -> {
            if (gateway.supportedCurrencies().contains(currency)) {
                matching.add(type);
            }
        });
        return matching;
    }

    /** Whether type is registered and settles the currency. */
    public boolean supports(PaymentGatewayType type, String currency) {
        PaymentGateway gateway = byType.get(type);
        return gateway != null && gateway.supportedCurrencies().contains(currency);
    }
}
