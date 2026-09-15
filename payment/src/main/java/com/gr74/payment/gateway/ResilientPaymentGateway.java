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
 * Wraps a {@link PaymentGateway} adapter in Resilience4j's fault-tolerance decorators — a circuit
 * breaker, a bulkhead and (for some calls) a retry — so the adapter itself stays a plain
 * {@code @Component} and the resilience lives in one place.
 *
 * <p><b>Why a decorator and not annotations (BUILD_PLAN 5.1, Decision 1):</b> the policy must vary
 * per <em>gateway instance</em>, not per method — Stripe and Paymob each get their own breaker and
 * bulkhead. Annotations name their instance with a compile-time literal, which cannot express "one
 * per {@link #type()}"; this class can, because every instance is named
 * {@code delegate.type().name().toLowerCase()} — {@code stripe}, {@code paymob}, {@code sandbox} —
 * and each registry resolves that name to its own configured policy. A new gateway is still just a
 * new adapter {@code @Component}; it is wrapped automatically (see {@code ResilienceConfig}) with no
 * registration list to maintain.
 *
 * <p><b>Why refund is not retried (Decision 2):</b> {@code refund} moves money <em>out</em>, and a
 * blind retry on a timed-out call risks a double refund. {@code RefundService}'s
 * {@code Σ refunds ≤ amount} invariant is checked locally and cannot see a refund the gateway
 * processed but never told us about. {@code createSession} and {@code fetchStatus} DO get a retry —
 * the first because the adapters forward the idempotency key (Stripe's {@code Idempotency-Key},
 * Paymob's {@code merchant_order_id}), which makes a retry non-duplicating, and the second because
 * it is a pure read. That is the interview line word for word: <em>"the retry is only safe because
 * the idempotency key makes it non-duplicating."</em>
 *
 * <p><b>Why the webhook parser is completely undecorated (requirement, not an option):</b>
 * {@link #parseAndVerifyWebhook} is local CPU work (HMAC + parse) with no network call — a retry is
 * meaningless — and a webhook rejected by <em>our own</em> breaker would make a real gateway retry
 * its delivery for hours. Webhooks are the ONLY path to {@code PAID} in this system
 * ({@code WebhookController}), so they must never be subject to a policy that can refuse them.
 * {@link #type()} and {@link #supportedCurrencies()} also delegate undecorated: they do no I/O, and
 * {@code type()} in particular is the {@link GatewayRegistry} map key — decorating it would break
 * registry construction.
 *
 * <p><b>Only {@link GatewayException} counts as a breaker failure — and only it is retried.</b>
 * A declined card is a <em>successful</em> call that returns a {@code FAILED} status through the
 * normal path; confusing the two would trip the breaker on ordinary business outcomes and refuse
 * healthy traffic. The breaker and retry are configured with only {@code GatewayException}
 * (breaker {@code record-exceptions}, retry {@code retry-exceptions}) — see the
 * {@code resilience4j.*} block in {@code application.yml}. That is also what keeps a breaker-open
 * fast-fail ({@code CallNotPermittedException}) and a bulkhead rejection from being retried.
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
        // Instance name = delegate.type() (lower-cased to match the resilience4j.* YAML keys).
        // Fetching eagerly fails fast at startup if the policy name is misconfigured, instead of
        // waiting for the first real payment to discover it.
        String name = delegate.type().name().toLowerCase(Locale.ROOT);
        this.circuitBreaker = circuitBreakerRegistry.circuitBreaker(name);
        this.bulkhead = bulkheadRegistry.bulkhead(name);
        this.retry = retryRegistry.retry(name);
    }

    @Override
    public PaymentGatewayType type() {
        // No I/O, and this is the registry map key — must never be decorated.
        return delegate.type();
    }

    @Override
    public Set<String> supportedCurrencies() {
        // No I/O. Undecorated so the currency guard and gateway picker never trip a policy.
        return delegate.supportedCurrencies();
    }

    @Override
    public GatewaySession createSession(GatewaySessionRequest request) {
        // Retry is safe: the idempotency key rides along (Stripe Idempotency-Key / Paymob
        // merchant_order_id), and the schema's partial unique index on PENDING attempts is the
        // backstop even if the gateway creates a session we never learn about.
        return withRetry(() -> delegate.createSession(request));
    }

    @Override
    public GatewayPaymentStatus fetchStatus(GatewayStatusQuery query) {
        // A pure read — a retry cannot duplicate a side effect.
        return withRetry(() -> delegate.fetchStatus(query));
    }

    @Override
    public RefundResult refund(GatewayRefundRequest request) {
        // NO retry: see the class javadoc — a blind retry on a timed-out refund risks a double
        // refund, which is real money with no local invariant able to catch it. Breaker + bulkhead
        // still apply so a sick gateway neither parks a thread nor is hammered.
        return withoutRetry(() -> delegate.refund(request));
    }

    @Override
    public GatewayEvent parseAndVerifyWebhook(String rawPayload, Map<String, String> headers) {
        // Completely undecorated — see the class javadoc. Local CPU work, and a webhook must never
        // be refused by our own breaker.
        return delegate.parseAndVerifyWebhook(rawPayload, headers);
    }

    /**
     * The full stack for calls that may be retried: retry outermost so every attempt is counted by
     * the breaker, then the breaker so an open one short-circuits <em>before</em> consuming a
     * bulkhead permit, then the bulkhead so only a bounded number of real gateway calls are ever
     * in flight.
     */
    private <T> T withRetry(Supplier<T> call) {
        try {
            return retry.executeSupplier(() -> circuitBreaker.executeSupplier(() -> bulkhead.executeSupplier(call)));
        } catch (BulkheadFullException e) {
            throw bulkheadFull(e);
        }
    }

    /**
     * Breaker + bulkhead only — for {@code refund}, where a retry would be unsafe.
     */
    private <T> T withoutRetry(Supplier<T> call) {
        try {
            return circuitBreaker.executeSupplier(() -> bulkhead.executeSupplier(call));
        } catch (BulkheadFullException e) {
            throw bulkheadFull(e);
        }
    }

    /**
     * Translate a resilience bulkhead rejection into a coded domain exception. The bulkhead being
     * full is a local condition — "we are saturated, try again shortly" — so it carries the
     * distinct {@code PAYMENT_GATEWAY_BUSY} (429), not the outage 503, and it never records as a
     * breaker failure (a full bulkhead says nothing about the gateway's health).
     */
    private GatewayBusyException bulkheadFull(BulkheadFullException e) {
        log.debug("Bulkhead full for gateway {}, rejecting call: {}", delegate.type(), e.getMessage());
        return new GatewayBusyException(delegate.type(), e.getMessage(), e);
    }
}