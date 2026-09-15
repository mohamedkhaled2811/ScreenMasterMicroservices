package com.gr74.payment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.gr74.payment.client.BookingClient;
import com.gr74.payment.client.BookingPayability;
import com.gr74.payment.dto.CreatePaymentRequest;
import com.gr74.payment.exception.PaymentErrorCode;
import com.gr74.payment.exception.PaymentException;
import com.gr74.payment.gateway.GatewayException;
import com.gr74.payment.model.PaymentAttempt;
import com.gr74.payment.model.PaymentAttemptStatus;
import com.gr74.payment.model.PaymentGatewayType;
import com.gr74.payment.repository.PaymentAttemptRepository;
import com.gr74.payment.repository.PaymentRepository;

/**
 * The outage-retry path through the real stack: {@code POST /payments} while the gateway is down,
 * then {@code POST} again.
 *
 * <p>Before the stranded-retry fix, the second call never reached the gateway: the first call's
 * evidence row (PENDING, no session) tripped the one-live-attempt index on the retry's insert, and
 * the user got {@code 400 PAYMENT_VALIDATION_ERROR "already being created"} — hiding the outage
 * (and starving the circuit breaker) behind a validation error, with no sweeper or reconciliation
 * query ever matching the session-less row. These tests pin the fixed behaviour: every retry
 * re-calls the gateway on the <em>same</em> row with the <em>same</em> idempotency key, so a down
 * gateway surfaces as {@code 503} every time and a recovered one completes the same attempt.
 *
 * <p>Runs the full context on H2 with the sandbox pinned to {@code unavailable-rate=1.0}, so every
 * gateway call throws {@link GatewayException} — the only exception the breaker records.
 */
@SpringBootTest
@TestPropertySource(properties = {
        "payment.gateway.sandbox.unavailable-rate=1.0",
        "payment.gateway.sandbox.latency-millis=0",
        "resilience4j.retry.instances.sandbox.max-attempts=1",
})
class StrandedAttemptRetryTest {

    private static final String USER = "11111111-1111-1111-1111-111111111111";
    private static final Long BOOKING_ID = 2001L;
    private static final BigDecimal AMOUNT = new BigDecimal("300.00");

    /** The dependency the guards read; everything else is real, including the gateway call. */
    @MockitoBean
    private BookingClient bookingClient;

    @Autowired private PaymentService paymentService;
    @Autowired private PaymentRepository payments;
    @Autowired private PaymentAttemptRepository attempts;
    @Autowired private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("delete from payment_attempts");
        jdbcTemplate.update("delete from payments");
        given(bookingClient.fetchPayability(BOOKING_ID)).willReturn(
                new BookingPayability(BOOKING_ID, USER, "PENDING",
                        Instant.now().plus(10, ChronoUnit.MINUTES), AMOUNT, "EGP"));
    }

    @Test
    @DisplayName("REGRESSION: a retry while the gateway is down returns 503 on the SAME row — never 400")
    void retryWhileGatewayDownHitsGatewayAgainOnSameRow() {
        CreatePaymentRequest request = new CreatePaymentRequest(BOOKING_ID, PaymentGatewayType.SANDBOX);

        // First call: the attempt row is committed as evidence, then the gateway throws.
        assertThatThrownBy(() -> paymentService.createSession(request, USER))
                .isInstanceOf(GatewayException.class);
        List<PaymentAttempt> afterFirst = attempts.findAll();
        assertThat(afterFirst).hasSize(1);
        PaymentAttempt stranded = afterFirst.get(0);
        assertThat(stranded.getStatus()).isEqualTo(PaymentAttemptStatus.PENDING);
        assertThat(stranded.getGatewaySessionId()).isNull();
        assertThat(stranded.getCheckoutUrl()).isNull();
        // The provisional deadline: without it no sweeper could ever collect this row.
        assertThat(stranded.getExpiresAt()).isNotNull();
        String key = stranded.getIdempotencyKey();

        // Second call: the OLD behaviour 400'd here (index collision, gateway never touched).
        // Now it must re-call the gateway for the same row — and fail the same honest way.
        assertThatThrownBy(() -> paymentService.createSession(request, USER))
                .isInstanceOf(GatewayException.class)
                .as("a down gateway must surface as 503, not PAYMENT_VALIDATION_ERROR");

        List<PaymentAttempt> afterRetry = attempts.findAll();
        assertThat(afterRetry).hasSize(1);   // no second row — the index was never tested
        PaymentAttempt retried = afterRetry.get(0);
        assertThat(retried.getId()).isEqualTo(stranded.getId());
        assertThat(retried.getIdempotencyKey()).isEqualTo(key);  // same key: the re-call can't double-charge
        assertThat(retried.getStatus()).isEqualTo(PaymentAttemptStatus.PENDING);
    }

    @Test
    @DisplayName("the retry-while-down failure is coded 503 GATEWAY_UNAVAILABLE — never the 400")
    void retryFailureCarriesGatewayUnavailableCode() {
        CreatePaymentRequest request = new CreatePaymentRequest(BOOKING_ID, PaymentGatewayType.SANDBOX);

        assertThatThrownBy(() -> paymentService.createSession(request, USER))
                .isInstanceOf(GatewayException.class)
                .extracting(e -> ((PaymentException) e).errorCode())
                .isEqualTo(PaymentErrorCode.PAYMENT_GATEWAY_UNAVAILABLE);
        // The OLD behaviour threw PAYMENT_VALIDATION_ERROR here ("already being created").
        assertThatThrownBy(() -> paymentService.createSession(request, USER))
                .isInstanceOf(GatewayException.class)
                .extracting(e -> ((PaymentException) e).errorCode())
                .isEqualTo(PaymentErrorCode.PAYMENT_GATEWAY_UNAVAILABLE);
    }

    @Test
    @DisplayName("a fresh insert carries the provisional deadline for the sweeper backstop")
    void freshAttemptCarriesProvisionalDeadline() {
        CreatePaymentRequest request = new CreatePaymentRequest(BOOKING_ID, PaymentGatewayType.SANDBOX);

        assertThatThrownBy(() -> paymentService.createSession(request, USER))
                .isInstanceOf(GatewayException.class);

        Instant expiresAt = attempts.findAll().get(0).getExpiresAt();
        // Default stranded-ttl is 15 minutes: far enough out that an immediate retry still finds
        // the row stranded (not lapsed — lapsing is the sweeper's verdict, not the reader's).
        assertThat(expiresAt).isAfter(Instant.now().plus(10, ChronoUnit.MINUTES));
    }
}
