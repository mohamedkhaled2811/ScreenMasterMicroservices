package com.gr74.payment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

import com.gr74.payment.config.ReconciliationProps;
import com.gr74.payment.model.Payment;
import com.gr74.payment.model.PaymentAttempt;
import com.gr74.payment.model.PaymentAttemptStatus;
import com.gr74.payment.model.PaymentGatewayType;
import com.gr74.payment.model.PaymentStatus;
import com.gr74.payment.repository.PaymentAttemptRepository;
import com.gr74.payment.repository.PaymentRepository;

/**
 * The session-expiry sweeper against a frozen clock.
 *
 * <p>What it proves:
 * <ol>
 *   <li>a PENDING attempt past {@code expires_at} <em>and</em> past the reconciliation window
 *       flips to EXPIRED, while the payment stays PENDING for Pay Again;</li>
 *   <li><b>the ordering constraint</b> (the regression test that matters most here): a PENDING
 *       attempt past {@code expires_at} but still <em>inside</em> the reconciliation window is
 *       left alone — reconciliation decides first, blind expiry is only for what the gateway has
 *       disowned;</li>
 *   <li>live sessions and terminal attempts are untouched, and re-runs are no-ops;</li>
 *   <li>a tick whose repository throws still does not kill the scheduler (caught internally).</li>
 * </ol>
 */
@DataJpaTest
@Import(AttemptExpirySweeper.class)
// The suite-wide kill-switch (payment.sweeps.enabled=false) would condition this @Import'ed bean
// away, and this class tests the sweeper itself. Re-enable it here: @DataJpaTest has no
// scheduler, so the bean still never ticks — every sweep stays directly driven and frozen-clock.
@TestPropertySource(properties = "payment.sweeps.enabled=true")
class AttemptExpirySweeperTest {

    private static final Instant NOW = Instant.parse("2026-09-06T20:16:00Z");
    private static final String USER = "11111111-1111-1111-1111-111111111111";

    @TestConfiguration
    static class FixedTimeConfig {
        @Bean
        Clock clock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }

        @Bean
        ReconciliationProps reconciliationProps() {
            return new ReconciliationProps(Duration.ofMinutes(10), 100);
        }
    }

    @Autowired
    private AttemptExpirySweeper sweeper;

    @Autowired
    private PaymentRepository payments;

    @Autowired
    private PaymentAttemptRepository attempts;

    @Test
    @DisplayName("lapsed session past the reconciliation window expires; the payment stays PENDING")
    void disownedSessionExpiresButPaymentStaysPayable() {
        PaymentAttempt attempt = persistAttempt(1001L, NOW.minus(Duration.ofMinutes(11)));

        int expired = sweeper.expireLapsedAttempts(NOW);

        assertThat(expired).isEqualTo(1);
        assertThat(attempts.findById(attempt.getId()).orElseThrow().getStatus())
                .isEqualTo(PaymentAttemptStatus.EXPIRED);
        assertThat(payments.findById(attempt.getPayment().getId()).orElseThrow().getStatus())
                .isEqualTo(PaymentStatus.PENDING);
        // Re-runs are no-ops through the terminal-state guard.
        assertThat(sweeper.expireLapsedAttempts(NOW)).isZero();
    }

    @Test
    @DisplayName("REGRESSION: a lapsed session inside the reconciliation window is LEFT PENDING")
    void lapsedButInsideReconciliationWindowIsLeftForReconciliation() {
        PaymentAttempt attempt = persistAttempt(1002L, NOW.minus(Duration.ofMinutes(1)));

        int expired = sweeper.expireLapsedAttempts(NOW);

        assertThat(expired).isZero();
        assertThat(attempts.findById(attempt.getId()).orElseThrow().getStatus())
                .isEqualTo(PaymentAttemptStatus.PENDING);
    }

    @Test
    @DisplayName("live sessions and terminal attempts are untouched")
    void liveAndTerminalAttemptsAreUntouched() {
        PaymentAttempt live = persistAttempt(1003L, NOW.plus(Duration.ofMinutes(5)));
        PaymentAttempt done = persistAttempt(1004L, NOW.minus(Duration.ofHours(1)));
        done.transitionTo(PaymentAttemptStatus.SUCCEEDED, "gw-pay-1", null);
        attempts.saveAndFlush(done);

        assertThat(sweeper.expireLapsedAttempts(NOW)).isZero();

        assertThat(attempts.findById(live.getId()).orElseThrow().getStatus())
                .isEqualTo(PaymentAttemptStatus.PENDING);
        assertThat(attempts.findById(done.getId()).orElseThrow().getStatus())
                .isEqualTo(PaymentAttemptStatus.SUCCEEDED);
    }

    @Test
    @DisplayName("BACKSTOP: a stranded attempt past its provisional deadline expires — the payment is payable again")
    void strandedAttemptPastProvisionalDeadlineExpires() {
        // The gateway never answered: no session recorded, only the provisional deadline the
        // insert stamped. Past the reconciliation window, so the sweeper — not reconciliation,
        // which can only probe attempts WITH a session id — is the right owner.
        PaymentAttempt stranded = persistStrandedAttempt(1005L, NOW.minus(Duration.ofMinutes(11)));

        int expired = sweeper.expireLapsedAttempts(NOW);

        assertThat(expired).isEqualTo(1);
        assertThat(attempts.findById(stranded.getId()).orElseThrow().getStatus())
                .isEqualTo(PaymentAttemptStatus.EXPIRED);
        assertThat(payments.findById(stranded.getPayment().getId()).orElseThrow().getStatus())
                .isEqualTo(PaymentStatus.PENDING);
    }

    @Test
    @DisplayName("a tick whose repository throws does not kill the scheduler")
    void tickCatchesRepositoryFailureInsteadOfKillingTheScheduler() {        PaymentAttemptRepository failing = mock(PaymentAttemptRepository.class);
        given(failing.findLapsed(any(), any())).willThrow(new RuntimeException("db down"));
        AttemptExpirySweeper fragile = new AttemptExpirySweeper(
                failing, Clock.fixed(NOW, ZoneOffset.UTC), new ReconciliationProps(Duration.ofMinutes(10), 100));

        assertThatNoException().isThrownBy(fragile::tick);
    }

    private PaymentAttempt persistAttempt(long bookingId, Instant expiresAt) {        Payment payment = new Payment(bookingId, USER, new BigDecimal("300.00"), "EGP");
        PaymentAttempt attempt =
                new PaymentAttempt(PaymentGatewayType.SANDBOX, "idem-" + bookingId + "-" + expiresAt.getEpochSecond());
        payment.addAttempt(attempt);
        attempt.recordSession("sess-" + bookingId, "http://localhost/checkout/sess-" + bookingId, expiresAt);
        payments.saveAndFlush(payment);
        return attempt;
    }

    /**
     * A stranded row as the insert leaves it when the gateway throws: PENDING, no session, no
     * checkout — only the provisional deadline. Deliberately NO recordSession call.
     */
    private PaymentAttempt persistStrandedAttempt(long bookingId, Instant provisionalExpiresAt) {
        Payment payment = new Payment(bookingId, USER, new BigDecimal("300.00"), "EGP");
        PaymentAttempt attempt = new PaymentAttempt(PaymentGatewayType.SANDBOX,
                "idem-stranded-" + bookingId, provisionalExpiresAt);
        payment.addAttempt(attempt);
        payments.saveAndFlush(payment);
        return attempt;
    }
}
