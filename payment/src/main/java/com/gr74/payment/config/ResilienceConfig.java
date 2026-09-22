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
 * Wraps every gateway adapter in a {@link ResilientPaymentGateway} for the registry to consume.
 */
@Slf4j
@Configuration
public class ResilienceConfig {

    /** The decorated gateway list, built from the per-gateway Resilience4j registries. */
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