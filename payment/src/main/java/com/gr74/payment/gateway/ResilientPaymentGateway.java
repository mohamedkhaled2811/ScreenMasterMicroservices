package com.gr74.payment.gateway;

import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

import com.gr74.payment.exception.GatewayBusyException;
import com.gr74.payment.model.PaymentGatewayType;

import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryRegistry;
import lombok.extern.slf4j.Slf4j;

/**
 * Decorates an adapter with per-gateway circuit breaker, bulkhead, and retry.
 * Retry applies only to createSession (idempotency key) and fetchStatus (pure read), never refund;
 * webhook parsing and type() stay undecorated. Only GatewayException counts as a breaker failure.
 */
@Slf4j
public class ResilientPaymentGateway implements PaymentGateway {

    private final PaymentGateway delegate;
    private final CircuitBreaker circuitBreaker;
    private final Bulkhead bulkhead;
    private final Retry retry;

    public ResilientPaymentGateway(PaymentGateway delegate,
            CircuitBreakerRegistry circuitBreakerRegistry,
            BulkheadRegistry bulkheadRegistry,
            RetryRegistry retryRegistry) {
        this.delegate = delegate;
        // Instance name matches the resilience4j YAML keys; fetched eagerly to fail fast on misconfig.
        String name = delegate.type().name().toLowerCase(Locale.ROOT);
        this.circuitBreaker = circuitBreakerRegistry.circuitBreaker(name);
        this.bulkhead = bulkheadRegistry.bulkhead(name);
        this.retry = retryRegistry.retry(name);
    }

    @Override
    public PaymentGatewayType type() {
        // No I/O; registry map key, never decorated.
        return delegate.type();
    }

    @Override
    public Set<String> supportedCurrencies() {
        // No I/O; never decorated.
        return delegate.supportedCurrencies();
    }

    @Override
    public GatewaySession createSession(GatewaySessionRequest request) {
        // Retry safe via idempotency key.
        return withRetry(() -> delegate.createSession(request));
    }

    @Override
    public GatewayPaymentStatus fetchStatus(GatewayStatusQuery query) {
        // Pure read; retry cannot duplicate a side effect.
        return withRetry(() -> delegate.fetchStatus(query));
    }

    @Override
    public RefundResult refund(GatewayRefundRequest request) {
        // No retry: a blind retry on a timed-out refund risks a double refund.
        return withoutRetry(() -> delegate.refund(request));
    }

    @Override
    public GatewayEvent parseAndVerifyWebhook(String rawPayload, Map<String, String> headers) {
        // Undecorated local CPU work; webhooks must never be refused by our own breaker.
        return delegate.parseAndVerifyWebhook(rawPayload, headers);
    }

    /** Retry outermost, then breaker, then bulkhead. */
    private <T> T withRetry(Supplier<T> call) {
        try {
            return retry.executeSupplier(() -> circuitBreaker.executeSupplier(() -> bulkhead.executeSupplier(call)));
        } catch (BulkheadFullException e) {
            throw bulkheadFull(e);
        }
    }

    /** Breaker + bulkhead only, for refund. */
    private <T> T withoutRetry(Supplier<T> call) {
        try {
            return circuitBreaker.executeSupplier(() -> bulkhead.executeSupplier(call));
        } catch (BulkheadFullException e) {
            throw bulkheadFull(e);
        }
    }

    /** Translate bulkhead rejection to 429; never recorded as a breaker failure. */
    private GatewayBusyException bulkheadFull(BulkheadFullException e) {
        log.debug("Bulkhead full for gateway {}, rejecting call: {}", delegate.type(), e.getMessage());
        return new GatewayBusyException(delegate.type(), e.getMessage(), e);
    }
}