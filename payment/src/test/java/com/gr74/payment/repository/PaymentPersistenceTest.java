package com.gr74.payment.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.gr74.payment.client.BookingClient;
import com.gr74.payment.model.Payment;
import com.gr74.payment.model.PaymentAttempt;
import com.gr74.payment.model.PaymentAttemptStatus;
import com.gr74.payment.model.PaymentGatewayType;
import com.gr74.payment.model.PaymentStatus;
import com.gr74.payment.model.Refund;
import com.gr74.payment.model.RefundStatus;
import com.gr74.payment.model.WebhookEvent;

import jakarta.persistence.EntityManager;

/**
 * The payment schema on a real database — mappings, constraints, and the money arithmetic.
 *
 * <p>Runs on H2 with Hibernate generating the schema (see {@code src/test/resources/application.yml}),
 * so it proves the <em>entities</em> agree with themselves. The Liquibase changesets are the
 * production schema, and {@code ddl-auto=validate} is what proves the two agree — that check runs on
 * a real Postgres boot, not here.
 *
 * <p><b>Not covered here:</b> the partial unique index ({@code uq_active_attempt_per_payment}) is
 * Postgres-only, since H2 has no partial-index support. Its <em>behaviour</em> is asserted at the
 * service level instead ({@code PaymentServiceTest#reusesLiveAttempt}).
 */
@DataJpaTest
class PaymentPersistenceTest {

    /** Not used by these tests, but the slice loads the app's component scan. */
    @MockitoBean
    private BookingClient bookingClient;

    @Autowired private PaymentRepository payments;
    @Autowired private PaymentAttemptRepository attempts;
    @Autowired private RefundRepository refunds;
    @Autowired private WebhookEventRepository webhookEvents;
    @Autowired private EntityManager em;

    private Payment newPayment(long bookingId) {
        return new Payment(bookingId, "user-1", new BigDecimal("300.00"), "EGP");
    }

    @Test
    @DisplayName("one payment per booking — the constraint, not the read, is the guarantee")
    void enforcesOnePaymentPerBooking() {
        payments.saveAndFlush(newPayment(1001L));

        // The race two concurrent POST /payments would hit: both miss the read, both insert.
        assertThatThrownBy(() -> payments.saveAndFlush(newPayment(1001L)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("a payment accumulates many attempts")
    void paymentHoldsManyAttempts() {
        Payment payment = payments.saveAndFlush(newPayment(1002L));

        PaymentAttempt first = new PaymentAttempt(PaymentGatewayType.PAYMOB, "key-1");
        PaymentAttempt second = new PaymentAttempt(PaymentGatewayType.STRIPE, "key-2");
        payment.addAttempt(first);
        payment.addAttempt(second);
        payments.saveAndFlush(payment);
        em.flush();
        em.clear();

        assertThat(attempts.findByPaymentIdOrderByCreatedAtAsc(payment.getId()))
                .hasSize(2)
                .extracting(PaymentAttempt::getGateway)
                .containsExactlyInAnyOrder(PaymentGatewayType.PAYMOB, PaymentGatewayType.STRIPE);
    }

    @Test
    @DisplayName("an attempt's idempotency key is unique across all attempts")
    void enforcesUniqueIdempotencyKey() {
        Payment payment = payments.saveAndFlush(newPayment(1003L));
        PaymentAttempt first = new PaymentAttempt(PaymentGatewayType.SANDBOX, "duplicate-key");
        payment.addAttempt(first);
        attempts.saveAndFlush(first);

        Payment other = payments.saveAndFlush(newPayment(1004L));
        PaymentAttempt clash = new PaymentAttempt(PaymentGatewayType.SANDBOX, "duplicate-key");
        other.addAttempt(clash);

        assertThatThrownBy(() -> attempts.saveAndFlush(clash))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("a terminal attempt ignores further transitions — out-of-order webhooks are safe")
    void terminalAttemptIgnoresLaterTransitions() {
        PaymentAttempt attempt = new PaymentAttempt(PaymentGatewayType.STRIPE, "key-terminal");

        assertThat(attempt.transitionTo(PaymentAttemptStatus.SUCCEEDED, "pi_1", null)).isTrue();
        // A delayed PaymentFailed arriving after the success must NOT overwrite it.
        assertThat(attempt.transitionTo(PaymentAttemptStatus.FAILED, "pi_1", "too late")).isFalse();
        assertThat(attempt.getStatus()).isEqualTo(PaymentAttemptStatus.SUCCEEDED);
        assertThat(attempt.getFailureReason()).isNull();
    }

    @Test
    @DisplayName("markPaid is idempotent — a redelivered webhook must not re-publish")
    void markPaidIsIdempotent() {
        Payment payment = newPayment(1005L);

        assertThat(payment.markPaid()).isTrue();    // first webhook: state changed, publish
        assertThat(payment.markPaid()).isFalse();   // redelivery: no change, do NOT publish again
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PAID);
    }

    @Test
    @DisplayName("partial refunds accumulate and drive the payment's status")
    void partialRefundsAccumulate() {
        // The plan's worked example: 1000 refunded 300 then 200 leaves 500 refundable.
        Payment payment = new Payment(1006L, "user-1", new BigDecimal("1000.00"), "EGP");
        payment.markPaid();

        payment.applyRefund(new BigDecimal("300.00"));
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PARTIALLY_REFUNDED);
        assertThat(payment.remainingRefundable()).isEqualByComparingTo("700.00");

        payment.applyRefund(new BigDecimal("200.00"));
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PARTIALLY_REFUNDED);
        assertThat(payment.remainingRefundable()).isEqualByComparingTo("500.00");

        payment.applyRefund(new BigDecimal("500.00"));
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.REFUNDED);
        assertThat(payment.remainingRefundable()).isEqualByComparingTo("0.00");
    }

    @Test
    @DisplayName("a refund only counts once confirmed")
    void refundConfirmationIsIdempotent() {
        Refund refund = new Refund(new BigDecimal("100.00"), "BOOKING_EXPIRED", "refund-key-1");

        assertThat(refund.getStatus()).isEqualTo(RefundStatus.PENDING);
        assertThat(refund.markSucceeded("re_1")).isTrue();
        // A redelivered refund webhook must not let the caller add the amount to the total twice.
        assertThat(refund.markSucceeded("re_1")).isFalse();
    }

    @Test
    @DisplayName("refund idempotency keys are unique — the same compensation cannot refund twice")
    void enforcesUniqueRefundKey() {
        Payment payment = payments.saveAndFlush(newPayment(1007L));
        Refund first = new Refund(new BigDecimal("50.00"), "BOOKING_EXPIRED", "same-key");
        payment.addRefund(first);
        refunds.saveAndFlush(first);

        Refund duplicate = new Refund(new BigDecimal("50.00"), "BOOKING_EXPIRED", "same-key");
        payment.addRefund(duplicate);

        assertThatThrownBy(() -> refunds.saveAndFlush(duplicate))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("the same gateway event cannot be stored twice — the dedupe IS the insert")
    void enforcesWebhookDedupe() {
        webhookEvents.saveAndFlush(new WebhookEvent(
                PaymentGatewayType.STRIPE, "evt_1", "checkout.session.completed",
                "{\"id\":\"evt_1\"}", "{}", true));

        // The gateway redelivering evt_1 fails the insert; the handler answers 200 and does nothing.
        assertThatThrownBy(() -> webhookEvents.saveAndFlush(new WebhookEvent(
                PaymentGatewayType.STRIPE, "evt_1", "checkout.session.completed",
                "{\"id\":\"evt_1\"}", "{}", true)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("the same event id from DIFFERENT gateways is not a duplicate")
    void dedupeIsScopedPerGateway() {
        // Two gateways could independently mint "evt_1"; scoping the key by gateway stops one
        // gateway's event silently suppressing another's.
        webhookEvents.saveAndFlush(new WebhookEvent(
                PaymentGatewayType.STRIPE, "evt_1", "t", "{}", "{}", true));
        webhookEvents.saveAndFlush(new WebhookEvent(
                PaymentGatewayType.PAYMOB, "evt_1", "t", "{}", "{}", true));

        assertThat(webhookEvents.findByGatewayAndEventId(PaymentGatewayType.STRIPE, "evt_1")).isPresent();
        assertThat(webhookEvents.findByGatewayAndEventId(PaymentGatewayType.PAYMOB, "evt_1")).isPresent();
    }

    @Test
    @DisplayName("a failed-signature delivery is stored, not discarded — it is the audit trail")
    void storesFailedSignatures() {
        WebhookEvent forged = webhookEvents.saveAndFlush(new WebhookEvent(
                PaymentGatewayType.STRIPE, "evt_forged", "checkout.session.completed",
                "{\"forged\":true}", "{}", false));

        assertThat(forged.isSignatureValid()).isFalse();
        assertThat(forged.getPayload()).contains("forged");
    }

    @Test
    @DisplayName("a lapsed session is found by the sweeper; a live one is not")
    void findsLapsedAttempts() {
        Payment payment = payments.saveAndFlush(newPayment(1008L));

        PaymentAttempt lapsed = new PaymentAttempt(PaymentGatewayType.SANDBOX, "key-lapsed");
        lapsed.recordSession("s-lapsed", "url", Instant.now().minus(5, ChronoUnit.MINUTES));
        payment.addAttempt(lapsed);
        attempts.saveAndFlush(lapsed);

        PaymentAttempt live = new PaymentAttempt(PaymentGatewayType.SANDBOX, "key-live");
        live.recordSession("s-live", "url", Instant.now().plus(5, ChronoUnit.MINUTES));
        payment.addAttempt(live);
        attempts.saveAndFlush(live);

        assertThat(attempts.findLapsed(PaymentAttemptStatus.PENDING, Instant.now()))
                .extracting(PaymentAttempt::getIdempotencyKey)
                .containsExactly("key-lapsed");
    }

    @Test
    @DisplayName("an attempt is live only while PENDING, with a URL, and before its deadline")
    void isLiveAtCoversEveryCondition() {
        Instant now = Instant.now();
        PaymentAttempt attempt = new PaymentAttempt(PaymentGatewayType.SANDBOX, "key-live-check");

        // No session yet — the gateway call has not returned, so there is nothing to reuse.
        assertThat(attempt.isLiveAt(now)).isFalse();

        attempt.recordSession("s1", "https://pay.example/x", now.plus(5, ChronoUnit.MINUTES));
        assertThat(attempt.isLiveAt(now)).isTrue();

        // Past its deadline: the SESSION is dead (the booking may well still be fine).
        assertThat(attempt.isLiveAt(now.plus(10, ChronoUnit.MINUTES))).isFalse();

        // Terminal: never reusable, regardless of the clock.
        attempt.transitionTo(PaymentAttemptStatus.FAILED, null, "declined");
        assertThat(attempt.isLiveAt(now)).isFalse();
    }
}
