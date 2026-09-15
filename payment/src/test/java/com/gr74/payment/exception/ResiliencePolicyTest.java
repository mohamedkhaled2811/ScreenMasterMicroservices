package com.gr74.payment.exception;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

import com.gr74.payment.model.PaymentGatewayType;

import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;

/**
 * The resilience <em>error contract</em>: a rejected call — breaker open or bulkhead full — must
 * render as a coded RFC 9457 ProblemDetail, and can never leak as an opaque 500.
 *
 * <p>Companion to {@code ResilientPaymentGatewayTest}, which proves the policy behaviour itself;
 * this class proves the {@link GlobalExceptionHandler} mappings that turn those policies'
 * exceptions into responses a caller can branch on.
 */
class ResiliencePolicyTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    @DisplayName("breaker open (CallNotPermittedException) renders as 503 PAYMENT_GATEWAY_UNAVAILABLE")
    void callNotPermittedMapsTo503() {
        CircuitBreaker circuitBreaker = CircuitBreaker.ofDefaults("paymob");
        CallNotPermittedException ex =
                CallNotPermittedException.createCallNotPermittedException(circuitBreaker);

        ProblemDetail problem = handler.handleCallNotPermitted(ex);

        assertThat(problem.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE.value());
        assertThat(problem.getProperties().get("code")).isEqualTo("PAYMENT_GATEWAY_UNAVAILABLE");
    }

    @Test
    @DisplayName("bulkhead full (BulkheadFullException) renders as 429 PAYMENT_GATEWAY_BUSY")
    void bulkheadFullMapsTo429() {
        Bulkhead bulkhead = Bulkhead.ofDefaults("paymob");
        BulkheadFullException ex = BulkheadFullException.createBulkheadFullException(bulkhead);

        ProblemDetail problem = handler.handleBulkheadFull(ex);

        assertThat(problem.getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS.value());
        assertThat(problem.getProperties().get("code")).isEqualTo("PAYMENT_GATEWAY_BUSY");
    }

    @Test
    @DisplayName("GatewayBusyException (what the decorator throws) renders as 429 PAYMENT_GATEWAY_BUSY")
    void gatewayBusyExceptionRendersAs429() {
        // The decorator translates BulkheadFullException into this coded domain exception; the
        // generic PaymentException handler picks the status and code up from the enum.
        PaymentException busy = new GatewayBusyException(PaymentGatewayType.PAYMOB, "8 concurrent calls in flight");

        ProblemDetail problem = handler.handlePaymentException(busy);

        assertThat(problem.getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS.value());
        assertThat(problem.getProperties().get("code")).isEqualTo("PAYMENT_GATEWAY_BUSY");
        assertThat(problem.getDetail()).contains("PAYMOB");
    }

    @Test
    @DisplayName("an outage and a busy gateway stay distinguishable in the code property")
    void busyAndUnavailableAreDistinctCodes() {
        // The two conditions must never collapse into one code: a 429 says "try again shortly",
        // a 503 says "the gateway is down". A client that branches on code depends on this.
        assertThat(PaymentErrorCode.PAYMENT_GATEWAY_BUSY.status())
                .isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(PaymentErrorCode.PAYMENT_GATEWAY_UNAVAILABLE.status())
                .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }
}