package com.gr74.payment.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.gr74.payment.exception.GatewayBusyException;
import com.gr74.payment.exception.PaymentErrorCode;
import com.gr74.payment.model.PaymentAttemptStatus;
import com.gr74.payment.model.PaymentGatewayType;
import com.gr74.payment.model.RefundStatus;

import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;

/**
 * Decorator behaviour with real Resilience4j registries and a mock delegate.
 */
class ResilientPaymentGatewayTest {

    @Test
    @DisplayName("every method reaches the delegate with unchanged arguments and return value")
    void delegatesTransparently() {
        PaymentGateway delegate = mock(PaymentGateway.class);
        given(delegate.type()).willReturn(PaymentGatewayType.SANDBOX);
        given(delegate.supportedCurrencies()).willReturn(Set.of("EGP", "USD"));

        GatewaySessionRequest request = sessionRequest();
        GatewaySession session = new GatewaySession("sbx_sess_1", "http://checkout/sbx_sess_1", Instant.now());
        GatewayStatusQuery query = GatewayStatusQuery.of("sbx_sess_1", "pm_1");
        GatewayPaymentStatus status = new GatewayPaymentStatus(PaymentAttemptStatus.SUCCEEDED, "pm_1", null);
        GatewayRefundRequest refundRequest = refundRequest();
        RefundResult refundResult = new RefundResult(RefundStatus.PENDING, "sbx_ref_1", null);
        Map<String, String> headers = Map.of("x-sandbox-signature", "sig");
        GatewayEvent event = new GatewayEvent("evt_1", "payment.succeeded", "sbx_sess_1", "pm_1",
                PaymentAttemptStatus.SUCCEEDED, null);

        given(delegate.createSession(request)).willReturn(session);
        given(delegate.fetchStatus(query)).willReturn(status);
        given(delegate.refund(refundRequest)).willReturn(refundResult);
        given(delegate.parseAndVerifyWebhook("raw", headers)).willReturn(event);

        ResilientPaymentGateway gateway = gateway(delegate, breaker(10, 50), bulkhead(8), retry(1));

        assertThat(gateway.type()).isEqualTo(PaymentGatewayType.SANDBOX);
        assertThat(gateway.supportedCurrencies()).containsExactlyInAnyOrder("EGP", "USD");
        assertThat(gateway.createSession(request)).isSameAs(session);
        assertThat(gateway.fetchStatus(query)).isSameAs(status);
        assertThat(gateway.refund(refundRequest)).isSameAs(refundResult);
        assertThat(gateway.parseAndVerifyWebhook("raw", headers)).isSameAs(event);

        verify(delegate).createSession(request);
        verify(delegate).fetchStatus(query);
        verify(delegate).refund(refundRequest);
        verify(delegate).parseAndVerifyWebhook("raw", headers);
    }

    @Test
    @DisplayName("breaker opens after the configured failures and then fast-fails with CallNotPermittedException")
    void breakerOpensAfterFailuresThenFastFails() {
        PaymentGateway delegate = mock(PaymentGateway.class);
        given(delegate.type()).willReturn(PaymentGatewayType.PAYMOB);
        given(delegate.createSession(any()))
                .willThrow(new GatewayException(PaymentGatewayType.PAYMOB, "simulated outage"));
        // retry disabled (maxAttempts=1) so one external call == one attempt == one breaker record.
        ResilientPaymentGateway gateway = gateway(delegate, breaker(4, 50), bulkhead(8), retry(1));

        // A 4-call sliding window: four straight infrastructural failures fill it at 100% -> OPEN.
        for (int i = 0; i < 4; i++) {
            assertThatThrownBy(() -> gateway.createSession(sessionRequest()))
                    .isInstanceOf(GatewayException.class);
        }

        // The fifth call must be refused in microseconds — a DIFFERENT exception, the fast-fail.
        assertThatThrownBy(() -> gateway.createSession(sessionRequest()))
                .isInstanceOf(CallNotPermittedException.class);
    }

    @Test
    @DisplayName("breaker-open fast-fail is not retried even with retry enabled")
    void breakerOpenIsNotRetried() {
        PaymentGateway delegate = mock(PaymentGateway.class);
        given(delegate.type()).willReturn(PaymentGatewayType.PAYMOB);
        given(delegate.createSession(any()))
                .willThrow(new GatewayException(PaymentGatewayType.PAYMOB, "simulated outage"));
        // Retry ENABLED (3 attempts), so ONE external call = 3 attempts = 3 breaker records. The
        // 4-record window fills on the FIRST attempt of the second call and the breaker opens.
        ResilientPaymentGateway gateway = gateway(delegate, breaker(4, 50), bulkhead(8), retry(3));

        // Call 1: three attempts, each fails, all recorded — window at 3/4, still CLOSED.
        assertThatThrownBy(() -> gateway.createSession(sessionRequest()))
                .isInstanceOf(GatewayException.class);

        // Call 2: the first attempt fills the window (4/4 = 100% > 50%) -> OPEN; the retry's next
        // attempt hits CallNotPermittedException, which is NOT a GatewayException and so is NOT
        // retried — the fast-fail propagates immediately, with no backoff sleep.
        assertThatThrownBy(() -> gateway.createSession(sessionRequest()))
                .isInstanceOf(CallNotPermittedException.class);

        // Three attempts (call 1) + one attempt (call 2's first) reached the delegate — the
        // open-state call never touched it.
        verify(delegate, times(4)).createSession(any());
    }

    @Test
    @DisplayName("a business decline (a normal FAILED return) does NOT trip the breaker")
    void businessDeclineDoesNotTripBreaker() {
        PaymentGateway delegate = mock(PaymentGateway.class);
        given(delegate.type()).willReturn(PaymentGatewayType.PAYMOB);
        // A declined card is a SUCCESSFUL gateway call that reports FAILED through the normal path —
        // reconciliation's view. It is not a GatewayException, so it must never count as a failure.
        GatewayPaymentStatus declined = new GatewayPaymentStatus(PaymentAttemptStatus.FAILED, "pm_1", "card_declined");
        given(delegate.fetchStatus(any())).willReturn(declined);

        CircuitBreakerRegistry cbr = CircuitBreakerRegistry.of(breaker(10, 50));
        BulkheadRegistry bhr = BulkheadRegistry.of(bulkhead(8));
        RetryRegistry rr = RetryRegistry.of(retry(1));
        ResilientPaymentGateway gateway = new ResilientPaymentGateway(delegate, cbr, bhr, rr);

        for (int i = 0; i < 10; i++) {
            assertThat(gateway.fetchStatus(GatewayStatusQuery.of("sbx_sess_1", "pm_1"))).isSameAs(declined);
        }

        // Ten straight FAILED outcomes with a 50% threshold WOULD have opened a breaker if declines
        // counted. They don't — a decline is a successful call, so the breaker stays CLOSED.
        assertThat(cbr.circuitBreaker("paymob").getState())
                .isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    @DisplayName("retry fires on createSession (delegate invoked N times on a GatewayException)")
    void retryFiresOnCreateSession() {
        PaymentGateway delegate = mock(PaymentGateway.class);
        given(delegate.type()).willReturn(PaymentGatewayType.STRIPE);
        given(delegate.createSession(any()))
                .willThrow(new GatewayException(PaymentGatewayType.STRIPE, "one dropped packet"));
        // 3 attempts = the delegate is tried up to 3 times for a single external call.
        ResilientPaymentGateway gateway = gateway(delegate, breaker(10, 50), bulkhead(8), retry(3));

        assertThatThrownBy(() -> gateway.createSession(sessionRequest()))
                .isInstanceOf(GatewayException.class);

        verify(delegate, times(3)).createSession(any());
    }

    @Test
    @DisplayName("retry does NOT fire on refund (delegate invoked exactly once on failure)")
    void retryDoesNotFireOnRefund() {
        PaymentGateway delegate = mock(PaymentGateway.class);
        given(delegate.type()).willReturn(PaymentGatewayType.STRIPE);
        given(delegate.refund(any()))
                .willThrow(new GatewayException(PaymentGatewayType.STRIPE, "refund endpoint down"));
        // Same retry policy as createSession — but a refund must never be retried: a timed-out
        // refund may have been processed, and a blind retry risks a double refund (real money the
        // local Σ-refunds invariant cannot see). So exactly one delegate call.
        ResilientPaymentGateway gateway = gateway(delegate, breaker(10, 50), bulkhead(8), retry(3));

        assertThatThrownBy(() -> gateway.refund(refundRequest()))
                .isInstanceOf(GatewayException.class);

        verify(delegate, times(1)).refund(any());
    }

    @Test
    @DisplayName("webhook parsing works with the breaker forced OPEN — it must never be refused")
    void webhookParseWorksWhileBreakerOpen() {
        PaymentGateway delegate = mock(PaymentGateway.class);
        given(delegate.type()).willReturn(PaymentGatewayType.SANDBOX);
        Map<String, String> headers = Map.of("x-sandbox-signature", "sig");
        GatewayEvent event = new GatewayEvent("evt_1", "payment.succeeded", "sbx_sess_1", "pm_1",
                PaymentAttemptStatus.SUCCEEDED, null);
        given(delegate.parseAndVerifyWebhook("raw", headers)).willReturn(event);

        CircuitBreakerRegistry cbr = CircuitBreakerRegistry.of(breaker(4, 50));
        BulkheadRegistry bhr = BulkheadRegistry.of(bulkhead(8));
        RetryRegistry rr = RetryRegistry.of(retry(1));
        ResilientPaymentGateway gateway = new ResilientPaymentGateway(delegate, cbr, bhr, rr);
        cbr.circuitBreaker("sandbox").transitionToOpenState();

        // A webhook is local CPU work and the ONLY path to PAID; a real gateway would retry a
        // refused delivery for hours. It must go straight to the delegate whatever the breaker says.
        assertThat(gateway.parseAndVerifyWebhook("raw", headers)).isSameAs(event);
        verify(delegate).parseAndVerifyWebhook("raw", headers);
    }

    @Test
    @DisplayName("bulkhead isolates per gateway — a saturated Stripe cannot reject a Paymob call")
    void bulkheadIsolatesPerGateway() throws Exception {
        PaymentGateway stripeDelegate = mock(PaymentGateway.class);
        PaymentGateway paymobDelegate = mock(PaymentGateway.class);
        given(stripeDelegate.type()).willReturn(PaymentGatewayType.STRIPE);
        given(paymobDelegate.type()).willReturn(PaymentGatewayType.PAYMOB);

        GatewaySession paymobSession = new GatewaySession("pm_sess_1", "http://paymob/checkout", Instant.now());
        given(paymobDelegate.createSession(any())).willReturn(paymobSession);

        // Stripe hangs — the dangerous case (worse than down, because every call burns the full
        // timeout). The first call parks inside the delegate holding the ONLY permit.
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        given(stripeDelegate.createSession(any())).willAnswer(invocation -> {
            entered.countDown();
            release.await();
            return new GatewaySession("st_sess_1", "http://stripe/checkout", Instant.now());
        });

        CircuitBreakerRegistry cbr = CircuitBreakerRegistry.of(breaker(10, 50));
        BulkheadRegistry bhr = BulkheadRegistry.of(bulkhead(1));
        RetryRegistry rr = RetryRegistry.of(retry(1));
        // Both wrappers share the SAME registries — exactly like production — but their bulkheads are
        // named by type(), so Stripe and Paymob each have their own permit pool.
        ResilientPaymentGateway stripe = new ResilientPaymentGateway(stripeDelegate, cbr, bhr, rr);
        ResilientPaymentGateway paymob = new ResilientPaymentGateway(paymobDelegate, cbr, bhr, rr);

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<?> parked = executor.submit(() -> stripe.createSession(sessionRequest()));
            assertThat(entered.await(5, TimeUnit.SECONDS)).as("first Stripe call parked in the delegate").isTrue();

            // Stripe's bulkhead (max 1) is saturated -> rejected immediately, translated to the coded 429.
            assertThatThrownBy(() -> stripe.createSession(sessionRequest()))
                    .isInstanceOf(GatewayBusyException.class)
                    .extracting(e -> ((GatewayBusyException) e).errorCode())
                    .isEqualTo(PaymentErrorCode.PAYMENT_GATEWAY_BUSY);

            // Paymob shares the same thread pool in reality but a DIFFERENT bulkhead -> unaffected.
            assertThat(paymob.createSession(sessionRequest())).isSameAs(paymobSession);

            release.countDown();
            parked.get(5, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    // --- helpers -------------------------------------------------------------

    /** Fresh registries (all instances share one config) and a decorator over {@code delegate}. */
    private static ResilientPaymentGateway gateway(PaymentGateway delegate,
            CircuitBreakerConfig breaker, BulkheadConfig bulkhead, RetryConfig retry) {
        return new ResilientPaymentGateway(delegate,
                CircuitBreakerRegistry.of(breaker), BulkheadRegistry.of(bulkhead), RetryRegistry.of(retry));
    }

    private static CircuitBreakerConfig breaker(int slidingWindow, float failureRateThreshold) {
        return CircuitBreakerConfig.custom()
                .slidingWindowSize(slidingWindow)
                .failureRateThreshold(failureRateThreshold)
                .permittedNumberOfCallsInHalfOpenState(3)
                .recordExceptions(GatewayException.class)
                .build();
    }

    private static BulkheadConfig bulkhead(int maxConcurrentCalls) {
        return BulkheadConfig.custom()
                .maxConcurrentCalls(maxConcurrentCalls)
                .maxWaitDuration(Duration.ZERO)   // reject immediately, never queue
                .build();
    }

    private static RetryConfig retry(int maxAttempts) {
        return RetryConfig.custom()
                .maxAttempts(maxAttempts)
                .waitDuration(Duration.ZERO)      // no real backoff sleeps in tests
                .retryExceptions(GatewayException.class)
                .build();
    }

    private static GatewaySessionRequest sessionRequest() {
        return new GatewaySessionRequest(1L, 2L, 3L, "user-1", new BigDecimal("100.00"), "EGP",
                "idem-1", "http://localhost/return", "http://localhost/cancel");
    }

    private static GatewayRefundRequest refundRequest() {
        return new GatewayRefundRequest("pm_1", new BigDecimal("100.00"), "EGP", "idem-refund-1",
                "BOOKING_EXPIRED");
    }
}