package com.gr74.payment.gateway;

import static java.util.function.Function.identity;
import static java.util.stream.Collectors.toUnmodifiableMap;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.springframework.stereotype.Component;

import com.gr74.payment.exception.GatewayNotAvailableException;
import com.gr74.payment.model.PaymentGatewayType;

import lombok.extern.slf4j.Slf4j;

/**
 * The single dispatch point from a {@link PaymentGatewayType} to its adapter.
 *
 * <p>Spring injects <em>every</em> {@link PaymentGateway} bean as a {@code List} (see
 * {@code docs/concepts/spring-core-and-beans.md}), so this map builds itself: an adapter whose
 * credentials are absent is {@code @ConditionalOnProperty}'d out of the context entirely and simply
 * never appears here. That is why a misconfigured gateway is <em>never offered</em> rather than
 * failing at checkout time.
 *
 * <p>This class is the reason there is no {@code if (gateway == STRIPE) ... else if (gateway ==
 * PAYMOB)} chain anywhere in the service. Everything downstream holds the returned
 * {@link PaymentGateway} and never asks which one it is.
 */
@Slf4j
@Component
public class GatewayRegistry {

    private final Map<PaymentGatewayType, PaymentGateway> byType;

    public GatewayRegistry(List<PaymentGateway> gateways) {
        this.byType = gateways.stream().collect(toUnmodifiableMap(PaymentGateway::type, identity()));
        log.info("Registered payment gateways: {}", available());
    }

    /**
     * The adapter for {@code type}.
     *
     * @throws GatewayNotAvailableException (400) when nothing is registered for it — a client asked
     *                                      for a gateway this deployment has no credentials for
     */
    public PaymentGateway require(PaymentGatewayType type) {
        PaymentGateway gateway = byType.get(type);
        if (gateway == null) {
            throw new GatewayNotAvailableException(type, available());
        }
        return gateway;
    }

    /** Every registered gateway, sorted for a stable API response and log line. */
    public Set<PaymentGatewayType> available() {
        return new TreeSet<>(byType.keySet());
    }

    /**
     * The gateways that can settle {@code currency} — what the client's gateway picker is built
     * from. Filtering here rather than at checkout is what stops a user choosing Paymob for a USD
     * booking and only discovering the problem at the gateway.
     */
    public Set<PaymentGatewayType> availableFor(String currency) {
        Set<PaymentGatewayType> matching = new TreeSet<>(Comparator.naturalOrder());
        byType.forEach((type, gateway) -> {
            if (gateway.supportedCurrencies().contains(currency)) {
                matching.add(type);
            }
        });
        return matching;
    }

    /** Whether {@code type} is registered <em>and</em> settles {@code currency}. */
    public boolean supports(PaymentGatewayType type, String currency) {
        PaymentGateway gateway = byType.get(type);
        return gateway != null && gateway.supportedCurrencies().contains(currency);
    }
}
