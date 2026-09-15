package com.gr74.payment.config;

import java.util.List;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.gr74.payment.gateway.GatewayRegistry;
import com.gr74.payment.gateway.PaymentGateway;
import com.gr74.payment.gateway.ResilientPaymentGateway;

import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.RetryRegistry;
import lombok.extern.slf4j.Slf4j;

/**
 * The seam where resilience attaches (BUILD_PLAN 5.1, Decision 1 — Option A).
 *
 * <p>Every raw adapter (Stripe, Paymob, Sandbox) is wrapped in a
 * {@link ResilientPaymentGateway} and the <em>decorated</em> list is what
 * {@link GatewayRegistry} consumes. No caller changes: {@code PaymentSessionFactory},
 * {@code RefundService}, {@code ReconciliationJob} and {@code WebhookController} all hold a
 * {@link PaymentGateway} and never learn a decorator exists. A new gateway is still one
 * {@code @Component}; it is wrapped here automatically.
 *
 * <p><b>The one wiring subtlety:</b> raw adapters and decorated wrappers are the same interface,
 * so the two lists must never be confused. The raw adapters are collected here (the
 * {@code List<PaymentGateway>} parameter — element-type injection, which sees only the
 * {@code @Component} adapters, never this {@code List} bean) and the decorated list is returned
 * with a {@code @Qualifier}. {@link GatewayRegistry} injects the same qualifier, so Spring hands it
 * this pre-built list and never the raw adapters. Without the qualifier, the registry would collect
 * the raw adapters too and the whole point of the decorator would be silently lost.
 */
@Slf4j
@Configuration
public class ResilienceConfig {

    /**
     * The decorated gateway list.
     *
     * <p>The registries are the Resilience4j beans this service auto-configures from the
     * {@code resilience4j.*} block in {@code application.yml} — one named breaker / bulkhead /
     * retry per gateway, resolved by {@code delegate.type()}.
     */
    @Bean
    @Qualifier("resilientPaymentGateways")
    public List<PaymentGateway> resilientPaymentGateways(
            List<PaymentGateway> gateways,
            CircuitBreakerRegistry circuitBreakerRegistry,
            BulkheadRegistry bulkheadRegistry,
            RetryRegistry retryRegistry) {
        List<PaymentGateway> decorated = gateways.stream()
                .<PaymentGateway>map(gateway -> new ResilientPaymentGateway(
                        gateway, circuitBreakerRegistry, bulkheadRegistry, retryRegistry))
                .toList();
        log.info("Wrapped {} payment gateway(s) with resilience policies", decorated.size());
        return decorated;
    }
}