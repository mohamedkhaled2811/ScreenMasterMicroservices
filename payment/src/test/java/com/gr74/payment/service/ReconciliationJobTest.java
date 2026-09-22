package com.gr74.payment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.gr74.payment.config.ReconciliationProps;
import com.gr74.payment.gateway.GatewayEvent;
import com.gr74.payment.gateway.GatewayException;
import com.gr74.payment.gateway.GatewayPaymentStatus;
import com.gr74.payment.gateway.GatewayRefundRequest;
import com.gr74.payment.gateway.GatewayRegistry;
import com.gr74.payment.gateway.GatewaySession;
import com.gr74.payment.gateway.GatewaySessionRequest;
import com.gr74.payment.gateway.GatewayStatusQuery;
import com.gr74.payment.gateway.PaymentGateway;
import com.gr74.payment.gateway.RefundResult;
import com.gr74.payment.gateway.sandbox.SandboxGateway;
import com.gr74.payment.gateway.sandbox.SandboxWebhookClient;
import com.gr74.payment.model.Payment;
import com.gr74.payment.model.PaymentAttempt;
import com.gr74.payment.model.PaymentAttemptStatus;
import com.gr74.payment.model.PaymentGatewayType;
import com.gr74.payment.model.SandboxCharge;
import com.gr74.payment.repository.PaymentAttemptRepository;
import com.gr74.payment.repository.PaymentRepository;
import com.gr74.payment.repository.SandboxChargeRepository;
import com.gr74.payment.webhook.WebhookProcessor;

/**
 * Reconciliation sweep: stale attempts recover through the webhook handler.
 */
@SpringBootTest
class ReconciliationJobTest {

    private static final String USER = "11111111-1111-1111-1111-111111111111";
    private static final String SIGNATURE_HEADER = "x-sandbox-signature";

    @Autowired private WebhookProcessor processor;
    @Autowired private GatewayRegistry registry;
    @Autowired private SandboxGateway gateway;
    @Autowired private PaymentRepository payments;
    @Autowired private PaymentAttemptRepository attempts;
    @Autowired private SandboxChargeRepository charges;
    @Autowired private JdbcTemplate jdbc;

    /** The sandbox's outbound delivery never leaves the suite. */
    @MockitoBean private SandboxWebhookClient webhookClient;

    private static final Instant NOW = Instant.parse("2026-09-06T20:16:00Z");

    private long bookingSeq = 5000L;

    /**
     * The job under test, constructed — not autowired. The suite-wide kill-switch
     * ({@code payment.sweeps.enabled=false}) keeps the scheduled bean out of the context so no
     * wall-clock tick can race these frozen-clock fixtures; reconcile() is a pure method call on
     * real collaborators, exactly like the {@code blind}/{@code bounded} instances below.
     */
    private ReconciliationJob job;

    @BeforeEach
    void setUp() {
        jdbc.update("delete from outbox");
        jdbc.update("delete from webhook_events");
        jdbc.update("delete from refunds");
        jdbc.update("delete from payment_attempts");
        jdbc.update("delete from sandbox_charges");
        jdbc.update("delete from payments");
        job = new ReconciliationJob(attempts, processor, registry,
                new ReconciliationProps(Duration.ofMinutes(10), 100),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    @DisplayName("a stale attempt the gateway calls PAID goes PAID through the same handler")
    void stalePaidAttemptRecovered() {
        PaymentAttempt attempt = staleAttempt("sbx_rec_ok", 20, 10, "sbx_pay_rec_ok");
        charges.saveAndFlush(new SandboxCharge("sbx_rec_ok", "sbx_pay_rec_ok",
                PaymentAttemptStatus.SUCCEEDED, null));

        int recovered = job.reconcile(NOW);

        assertThat(recovered).isEqualTo(1);
        assertThat(attemptStatus(attempt)).isEqualTo("SUCCEEDED");
        assertThat(paymentStatus(attempt)).isEqualTo("PAID");
        assertThat(count("outbox")).isEqualTo(1);
        // The central mechanic: the gateway's answer was funneled through the webhook path, so a
        // recon:{id}:{status} evidence row exists in webhook_events.
        assertThat(jdbc.queryForObject(
                "select count(*) from webhook_events where event_id = 'recon:" + attempt.getId()
                        + ":SUCCEEDED'",
                Integer.class)).isEqualTo(1);

        // The real webhook arriving late collapses onto the one applied outcome: nothing changes.
        byte[] late = paymentPayload("evt_late_real", "payment.succeeded", "sbx_rec_ok",
                "sbx_pay_rec_ok", null);
        processor.process(PaymentGatewayType.SANDBOX, late, signed(late));

        assertThat(paymentStatus(attempt)).isEqualTo("PAID");
        assertThat(count("outbox")).isEqualTo(1);
    }

    @Test
    @DisplayName("a second sweep over an already-reconciled attempt is a no-op, not a second outcome")
    void secondSweepIsNoop() {
        PaymentAttempt attempt = staleAttempt("sbx_rec_twice", 20, 10, "sbx_pay_rec_twice");
        charges.saveAndFlush(new SandboxCharge("sbx_rec_twice", "sbx_pay_rec_twice",
                PaymentAttemptStatus.SUCCEEDED, null));

        assertThat(job.reconcile(NOW)).isEqualTo(1);
        // The attempt is terminal now, so the sweep has no input left — and even a forced re-apply
        // of the same synthetic id would hit the dedupe rather than re-apply.
        assertThat(job.reconcile(NOW)).isEqualTo(0);
        assertThat(count("outbox")).isEqualTo(1);
        assertThat(attemptStatus(attempt)).isEqualTo("SUCCEEDED");
    }

    @Test
    @DisplayName("never paid and past the session deadline → EXPIRED; the payment stays payable")
    void neverPaidPastExpiryExpires() {
        PaymentAttempt attempt = staleAttempt("sbx_rec_gone", 20, -5, null);

        int recovered = job.reconcile(NOW);

        assertThat(recovered).isEqualTo(1);
        assertThat(attemptStatus(attempt)).isEqualTo("EXPIRED");
        assertThat(paymentStatus(attempt)).isEqualTo("PENDING");
        assertThat(count("outbox")).isEqualTo(0);
        assertThat(jdbc.queryForObject(
                "select count(*) from webhook_events where event_id = 'recon:" + attempt.getId()
                        + ":EXPIRED'",
                Integer.class)).isEqualTo(1);
    }

    @Test
    @DisplayName("never paid but the session is still live → left PENDING for a later sweep")
    void liveSessionWithoutPaymentStaysPending() {
        PaymentAttempt attempt = staleAttempt("sbx_rec_live", 20, 10, null);

        assertThat(job.reconcile(NOW)).isEqualTo(0);
        assertThat(attemptStatus(attempt)).isEqualTo("PENDING");
        assertThat(count("webhook_events")).isEqualTo(0);
    }

    @Test
    @DisplayName("a gateway that cannot answer skips the attempt and never kills the tick")
    void gatewayFailureSkipsAndSurvives() {
        PaymentAttempt attempt = staleAttempt("sbx_rec_down", 20, 10, null);
        ReconciliationJob blind = new ReconciliationJob(
                attempts, processor, throwingRegistry(),
                new ReconciliationProps(Duration.ofMinutes(10), 100),
                Clock.fixed(NOW, ZoneOffset.UTC));

        assertThat(blind.reconcile(NOW)).isEqualTo(0);
        assertThat(attemptStatus(attempt)).isEqualTo("PENDING");
        assertThatNoException().isThrownBy(blind::tick);
    }

    @Test
    @DisplayName("the batch bound holds: three stale attempts with batch-size 1 recover exactly one")
    void batchSizeBoundsASweep() {
        for (int i = 0; i < 3; i++) {
            String session = "sbx_batch_" + i;
            staleAttempt(session, 20, 10, "sbx_pay_batch_" + i);
            charges.saveAndFlush(new SandboxCharge(session, "sbx_pay_batch_" + i,
                    PaymentAttemptStatus.SUCCEEDED, null));
        }
        ReconciliationJob bounded = new ReconciliationJob(
                attempts, processor, registry,
                new ReconciliationProps(Duration.ofMinutes(10), 1),
                Clock.fixed(NOW, ZoneOffset.UTC));

        assertThat(bounded.reconcile(NOW)).isEqualTo(1);
        assertThat(count("outbox")).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "select count(*) from payment_attempts where status = 'PENDING'", Integer.class))
                .isEqualTo(2);
    }

    // ---------------------------------------------------------------------------
    // Fixtures.
    // ---------------------------------------------------------------------------

    /**
     * A PENDING attempt with a session, backdated past the stale threshold.
     *
     * @param createdMinutesAgo how long ago the attempt was created (stale means past 10 min)
     * @param expiresInMinutes  session deadline relative to NOW (negative = already lapsed)
     * @param gatewayPaymentId  a transaction id the webhook may already have reported, if any
     */
    private PaymentAttempt staleAttempt(String sessionId, int createdMinutesAgo,
            int expiresInMinutes, String gatewayPaymentId) {
        Payment payment = new Payment(bookingSeq++, USER, new BigDecimal("300.00"), "EGP");
        PaymentAttempt attempt = new PaymentAttempt(PaymentGatewayType.SANDBOX, "key-" + UUID.randomUUID());
        payment.addAttempt(attempt);
        attempt.recordSession(sessionId, "http://localhost/checkout/" + sessionId,
                NOW.plus(expiresInMinutes, ChronoUnit.MINUTES));
        payments.saveAndFlush(payment);
        if (gatewayPaymentId != null) {
            jdbc.update("update payment_attempts set gateway_payment_id = ? where id = ?",
                    gatewayPaymentId, attempt.getId());
        }
        // created_at is immutable to JPA — backdate it the honest way, with SQL.
        jdbc.update("update payment_attempts set created_at = ? where id = ?",
                Timestamp.from(NOW.minus(createdMinutesAgo, ChronoUnit.MINUTES)), attempt.getId());
        return attempt;
    }

    /** A registry whose sandbox adapter cannot answer — the gateway-down stand-in. */
    private GatewayRegistry throwingRegistry() {
        PaymentGateway blind = new PaymentGateway() {
            @Override
            public PaymentGatewayType type() {
                return PaymentGatewayType.SANDBOX;
            }

            @Override
            public Set<String> supportedCurrencies() {
                return Set.of("EGP");
            }

            @Override
            public GatewaySession createSession(GatewaySessionRequest request) {
                throw new UnsupportedOperationException();
            }

            @Override
            public GatewayPaymentStatus fetchStatus(GatewayStatusQuery query) {
                throw new GatewayException(PaymentGatewayType.SANDBOX, "simulated status outage");
            }

            @Override
            public RefundResult refund(GatewayRefundRequest request) {
                throw new UnsupportedOperationException();
            }

            @Override
            public GatewayEvent parseAndVerifyWebhook(String rawPayload, Map<String, String> headers) {
                throw new UnsupportedOperationException();
            }
        };
        return new GatewayRegistry(List.of(blind));
    }

    private byte[] paymentPayload(String eventId, String type, String sessionId,
            String paymentId, String failureReason) {
        StringBuilder json = new StringBuilder("{\"id\":\"").append(eventId)
                .append("\",\"type\":\"").append(type)
                .append("\",\"sessionId\":\"").append(sessionId).append("\"");
        if (paymentId != null) {
            json.append(",\"paymentId\":\"").append(paymentId).append("\"");
        }
        if (failureReason != null) {
            json.append(",\"failureReason\":\"").append(failureReason).append("\"");
        }
        return json.append("}").toString().getBytes(StandardCharsets.UTF_8);
    }

    private Map<String, String> signed(byte[] body) {
        return Map.of(SIGNATURE_HEADER,
                gateway.sign(new String(body, StandardCharsets.UTF_8)));
    }

    private String attemptStatus(PaymentAttempt attempt) {
        return jdbc.queryForObject(
                "select status from payment_attempts where id = " + attempt.getId(), String.class);
    }

    private String paymentStatus(PaymentAttempt attempt) {
        return jdbc.queryForObject(
                "select status from payments where id = "
                        + "(select payment_id from payment_attempts where id = " + attempt.getId() + ")",
                String.class);
    }

    private int count(String table) {
        return jdbc.queryForObject("select count(*) from " + table, Integer.class);
    }
}
