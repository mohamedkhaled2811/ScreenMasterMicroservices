package com.gr74.payment.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import com.gr74.payment.gateway.GatewayException;
import com.gr74.payment.gateway.GatewayRegistry;
import com.gr74.payment.gateway.ResilientPaymentGateway;
import com.gr74.payment.model.PaymentGatewayType;

import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import io.github.resilience4j.springboot3.bulkhead.monitoring.endpoint.BulkheadEndpoint;
import io.github.resilience4j.springboot3.circuitbreaker.monitoring.endpoint.CircuitBreakerEndpoint;
import io.github.resilience4j.springboot3.retry.monitoring.endpoint.RetryEndpoint;

/**
 * Production resilience4j block binds the intended breaker, bulkhead and retry numbers.
 */
@SpringBootTest(properties = {
        "spring.config.location=file:src/main/resources/application.yml",
        // A UNIQUE in-memory DB, deliberately NOT the shared jdbc:h2:mem:payment the rest of the
        // suite uses: this context loads the main application.yml with ddl-auto=create-drop, and a
        // create-drop on a shared named database would wipe the other cached contexts' tables.
        "spring.datasource.url=jdbc:h2:mem:paymentyaml;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.liquibase.enabled=false",
        "eureka.client.enabled=false",
        "payment.amqp.listener.auto-startup=false",
        "payment.outbox.relay-interval-millis=3600000",
        "payment.reconciliation.sweep-interval-millis=3600000",
        "payment.expiry.sweep-interval-millis=3600000",
        "payment.gateway.sandbox.latency-millis=0",
        "server.port=0"
})
class ResilienceYamlConfigTest {

    @Autowired
    ApplicationContext ctx;
    @Autowired
    CircuitBreakerRegistry cbr;
    @Autowired
    BulkheadRegistry bhr;
    @Autowired
    RetryRegistry rr;
    @Autowired
    GatewayRegistry registry;

    @Test
    @DisplayName("the production YAML binds the breaker/bulkhead/retry numbers and recordExceptions")
    void productionYamlBindsPolicies() {
        // Stripe is not registered in this deployment (no credentials), but the YAML defines its
        // instance — asking the registry for it materializes it WITH the configured policy. This is
        // how the per-gateway config is proven to bind, not just the adapter that happens to exist.
        CircuitBreakerConfig breaker = cbr.circuitBreaker("stripe").getCircuitBreakerConfig();
        assertThat(breaker.getSlidingWindowSize()).isEqualTo(10);
        assertThat(breaker.getFailureRateThreshold()).isEqualTo(50f);
        assertThat(breaker.getPermittedNumberOfCallsInHalfOpenState()).isEqualTo(3);
        assertThat(breaker.getWaitIntervalFunctionInOpenState().apply(1)).isEqualTo(30_000L);

        // record-exceptions: [GatewayException] — an outage records, a random runtime error does not.
        assertThat(breaker.getRecordExceptionPredicate()
                .test(new GatewayException(PaymentGatewayType.STRIPE, "down"))).isTrue();
        assertThat(breaker.getRecordExceptionPredicate().test(new RuntimeException("boom"))).isFalse();

        assertThat(bhr.bulkhead("stripe").getBulkheadConfig().getMaxConcurrentCalls()).isEqualTo(8);
        // max-wait-duration: 0 — reject immediately, never queue.
        assertThat(bhr.bulkhead("stripe").getBulkheadConfig().getMaxWaitDuration())
                .isEqualTo(Duration.ZERO);

        RetryConfig retry = rr.retry("stripe").getRetryConfig();
        assertThat(retry.getMaxAttempts()).isEqualTo(3);
        assertThat(retry.getExceptionPredicate()
                .test(new GatewayException(PaymentGatewayType.STRIPE, "blip"))).isTrue();
        assertThat(retry.getExceptionPredicate().test(new RuntimeException("boom"))).isFalse();
    }

    @Test
    @DisplayName("the registry serves decorated gateways under the production config")
    void registryServesDecoratedGateways() {
        assertThat(registry.available()).containsExactly(PaymentGatewayType.SANDBOX);
        assertThat(registry.require(PaymentGatewayType.SANDBOX))
                .isInstanceOf(ResilientPaymentGateway.class);
    }

    @Test
    @DisplayName("the actuator endpoints enumerate all three configured gateways")
    void actuatorEndpointsSeeAllThreeInstances() {
        // Materialize the instances the YAML declares but this deployment does not register.
        cbr.circuitBreaker("stripe");
        cbr.circuitBreaker("paymob");
        cbr.circuitBreaker("sandbox");
        bhr.bulkhead("stripe");
        bhr.bulkhead("paymob");
        bhr.bulkhead("sandbox");
        rr.retry("stripe");
        rr.retry("paymob");
        rr.retry("sandbox");

        assertThat(ctx.getBean(CircuitBreakerEndpoint.class).getAllCircuitBreakers()
                .getCircuitBreakers().keySet())
                .containsExactlyInAnyOrder("stripe", "paymob", "sandbox");
        assertThat(ctx.getBean(BulkheadEndpoint.class).getAllBulkheads().getBulkheads())
                .containsExactlyInAnyOrder("stripe", "paymob", "sandbox");
        assertThat(ctx.getBean(RetryEndpoint.class).getAllRetries().getRetries())
                .containsExactlyInAnyOrder("stripe", "paymob", "sandbox");
    }
}