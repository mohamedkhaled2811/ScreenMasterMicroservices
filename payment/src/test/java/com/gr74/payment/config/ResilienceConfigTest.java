package com.gr74.payment.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import com.gr74.payment.gateway.GatewayRegistry;
import com.gr74.payment.gateway.ResilientPaymentGateway;
import com.gr74.payment.model.PaymentGatewayType;

import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryRegistry;
import io.github.resilience4j.springboot3.bulkhead.monitoring.endpoint.BulkheadEndpoint;
import io.github.resilience4j.springboot3.circuitbreaker.monitoring.endpoint.CircuitBreakerEndpoint;
import io.github.resilience4j.springboot3.retry.monitoring.endpoint.RetryEndpoint;

/**
 * The wiring proof (BUILD_PLAN 5.1, task 4): a real Spring context boots with the Resilience4j
 * starter, every gateway coming out of the registry is a {@link ResilientPaymentGateway} (never a
 * raw adapter), and the starter's actuator endpoints actually work on this Boot-4 stack — the
 * {@code AUTO-CONFIG VERDICT} the task 1 risk asked for, kept as a regression test.
 *
 * <p>The test profile deliberately configures no {@code resilience4j.*} keys, so the named
 * instances below are created with defaults — the point here is wiring, not policy numbers.
 */
@SpringBootTest
class ResilienceConfigTest {

    @Autowired
    ApplicationContext ctx;
    @Autowired
    GatewayRegistry registry;

    @Test
    @DisplayName("every gateway the registry serves is a ResilientPaymentGateway, never a raw adapter")
    void everyRegisteredGatewayIsDecorated() {
        assertThat(registry.available()).isNotEmpty();
        for (PaymentGatewayType type : registry.available()) {
            assertThat(registry.require(type))
                    .as("gateway %s must be wrapped by the decorator", type)
                    .isInstanceOf(ResilientPaymentGateway.class);
        }
    }

    @Test
    @DisplayName("one named breaker/bulkhead/retry exists per registered gateway")
    void oneNamedPolicyPerGateway() {
        CircuitBreakerRegistry cbr = ctx.getBean(CircuitBreakerRegistry.class);
        BulkheadRegistry bhr = ctx.getBean(BulkheadRegistry.class);
        RetryRegistry rr = ctx.getBean(RetryRegistry.class);

        for (PaymentGatewayType type : registry.available()) {
            String name = type.name().toLowerCase();
            assertThat(cbr.getAllCircuitBreakers()).extracting(CircuitBreaker::getName).contains(name);
            assertThat(bhr.getAllBulkheads()).extracting(Bulkhead::getName).contains(name);
            assertThat(rr.getAllRetries()).extracting(Retry::getName).contains(name);
        }
    }

    @Test
    @DisplayName("the starter's actuator endpoints are live and enumerate the named instances")
    void actuatorEndpointsWork() {
        CircuitBreakerEndpoint circuitBreakers = ctx.getBean(CircuitBreakerEndpoint.class);
        BulkheadEndpoint bulkheads = ctx.getBean(BulkheadEndpoint.class);
        RetryEndpoint retries = ctx.getBean(RetryEndpoint.class);

        String sandbox = PaymentGatewayType.SANDBOX.name().toLowerCase();
        assertThat(circuitBreakers.getAllCircuitBreakers().getCircuitBreakers().keySet()).contains(sandbox);
        assertThat(bulkheads.getAllBulkheads().getBulkheads()).contains(sandbox);
        assertThat(retries.getAllRetries().getRetries()).contains(sandbox);
    }
}