package com.gr74.payment.webhook;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import com.gr74.payment.gateway.GatewayEvent;
import com.gr74.payment.gateway.sandbox.SandboxGateway;
import com.gr74.payment.model.Payment;
import com.gr74.payment.model.PaymentAttempt;
import com.gr74.payment.model.PaymentGatewayType;
import com.gr74.payment.outbox.OutboxMessageRepository;
import com.gr74.payment.repository.PaymentAttemptRepository;
import com.gr74.payment.repository.PaymentRepository;
import com.gr74.payment.repository.SandboxChargeRepository;
import com.gr74.payment.repository.WebhookEventRepository;
import com.gr74.payment.service.WebhookWriter;
import com.zaxxer.hikari.HikariDataSource;

/**
 * The webhook handler's contract: verify, store, dedupe, apply — and the crash-safety between the
 * two transactions.
 *
 * <p>Runs the full context on H2 with the sandbox gateway (no external gateway can be reached — see
 * {@code src/test/resources/application.yml}). The relay's native {@code SKIP LOCKED} query never
 * runs here (H2 lacks it); what this suite proves is everything around it — the evidence store, the
 * dedupe, the guards, and that the outbox row shares the business write's fate. Note the relay tick
 * itself is silenced in tests ({@code payment.outbox.relay-interval-millis} is hours in test config)
 * so it never fires mid-suite.
 */
@SpringBootTest
class WebhookProcessorTest {

    private static final String USER = "11111111-1111-1111-1111-111111111111";
    private static final String SIGNATURE_HEADER = "x-sandbox-signature";

    @Autowired private WebhookProcessor processor;
    @Autowired private WebhookWriter writer;
    @Autowired private SandboxGateway gateway;
    @Autowired private PaymentRepository payments;
    @Autowired private PaymentAttemptRepository attempts;
    @Autowired private WebhookEventRepository events;
    @Autowired private OutboxMessageRepository outbox;
    @Autowired private SandboxChargeRepository charges;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private TransactionTemplate transactions;
    @Autowired private HikariDataSource dataSource;

    @BeforeEach
    void setUp() {
        jdbc.update("delete from outbox");
        jdbc.update("delete from webhook_events");
        jdbc.update("delete from payment_attempts");
        jdbc.update("delete from sandbox_charges");
        jdbc.update("delete from payments");
    }

    @Test
    @DisplayName("the same event 5x: one PAID, one stored event, one outbox row, five 200s")
    void replaySameEventFiveTimesConfirmsExactlyOnce() {
        openAttempt("sbx_replay");
        byte[] body = paymentPayload("evt_replay", "payment.succeeded", "sbx_replay", "pay_1", null);

        WebhookResult first = processor.process(PaymentGatewayType.SANDBOX, body, signed(body));
        assertThat(first.outcome()).isEqualTo(WebhookResult.Outcome.PROCESSED);
        for (int i = 0; i < 4; i++) {
            WebhookResult redelivery = processor.process(PaymentGatewayType.SANDBOX, body, signed(body));
            assertThat(redelivery.outcome()).isEqualTo(WebhookResult.Outcome.DUPLICATE);
        }

        assertThat(paymentStatus()).isEqualTo("PAID");
        assertThat(count("webhook_events")).isEqualTo(1);
        assertThat(count("outbox")).isEqualTo(1);
    }

    @Test
    @DisplayName("PaymentFailed arriving after PaymentSucceeded is dropped; the payment stays PAID")
    void outOfOrderFailureAfterSuccessIsDropped() {
        openAttempt("sbx_ooo");
        processor.process(PaymentGatewayType.SANDBOX,
                paymentPayload("evt_ooo_ok", "payment.succeeded", "sbx_ooo", "pay_1", null),
                signed(paymentPayload("evt_ooo_ok", "payment.succeeded", "sbx_ooo", "pay_1", null)));
        assertThat(paymentStatus()).isEqualTo("PAID");

        byte[] late = paymentPayload("evt_ooo_late", "payment.failed", "sbx_ooo", "pay_1", "card_declined");
        WebhookResult result = processor.process(PaymentGatewayType.SANDBOX, late, signed(late));

        // Dropped by transitionTo's terminal guard — still a 200, still stored, but IGNORED.
        assertThat(result.outcome()).isEqualTo(WebhookResult.Outcome.PROCESSED);
        assertThat(paymentStatus()).isEqualTo("PAID");
        assertThat(count("outbox")).isEqualTo(1); // the redelivery confirmed nothing twice
        assertThat(jdbc.queryForObject(
                "select processing_status from webhook_events where event_id = 'evt_ooo_late'",
                String.class)).isEqualTo("IGNORED");
    }

    @Test
    @DisplayName("forged signature: 400, a stored row with signature_valid=false, zero state change")
    void forgedSignatureIsStoredAndChangesNothing() {
        openAttempt("sbx_forge");
        byte[] body = paymentPayload("evt_forge", "payment.succeeded", "sbx_forge", "pay_1", null);

        // Signed with a different secret — the smallest possible forgery.
        Map<String, String> forged = Map.of(SIGNATURE_HEADER, "0".repeat(64));
        WebhookResult result = processor.process(PaymentGatewayType.SANDBOX, body, forged);

        assertThat(result.outcome()).isEqualTo(WebhookResult.Outcome.BAD_SIGNATURE);
        assertThat(jdbc.queryForObject(
                "select count(*) from webhook_events where signature_valid = false", Integer.class))
                .isEqualTo(1);
        assertThat(paymentStatus()).isEqualTo("PENDING");
        assertThat(count("outbox")).isEqualTo(0);
        assertThat(attemptStatus()).isEqualTo("PENDING");
    }

    @Test
    @DisplayName("missing signature header is a 400 with evidence, not a 500")
    void missingSignatureIsStoredAndChangesNothing() {
        openAttempt("sbx_nosig");
        byte[] body = paymentPayload("evt_nosig", "payment.succeeded", "sbx_nosig", "pay_1", null);

        WebhookResult result = processor.process(PaymentGatewayType.SANDBOX, body, Map.of());

        assertThat(result.outcome()).isEqualTo(WebhookResult.Outcome.BAD_SIGNATURE);
        assertThat(jdbc.queryForObject(
                "select count(*) from webhook_events where signature_valid = false", Integer.class))
                .isEqualTo(1);
        assertThat(paymentStatus()).isEqualTo("PENDING");
    }

    @Test
    @DisplayName("RECEIVED-but-never-applied is re-run: the crash between the two transactions heals")
    void receivedButNotProcessedIsReRun() {
        openAttempt("sbx_crash");
        byte[] body = paymentPayload("evt_crash", "payment.succeeded", "sbx_crash", "pay_1", null);

        // The first attempt committed its evidence row and died before applying — exactly the state
        // a crash between the two transactions leaves behind.
        GatewayEvent event = gateway.parseAndVerifyWebhook(asString(body), signed(body));
        var stored = writer.storeVerifiedEvent(PaymentGatewayType.SANDBOX, event, body, signed(body));
        assertThat(stored.getProcessingStatus().name()).isEqualTo("RECEIVED");
        assertThat(paymentStatus()).isEqualTo("PENDING");

        // The gateway redelivers (they always do). The dedupe fires, the row is only RECEIVED, so
        // the apply re-runs instead of being skipped — and every guard makes that re-run safe.
        WebhookResult result = processor.process(PaymentGatewayType.SANDBOX, body, signed(body));

        assertThat(result.outcome()).isEqualTo(WebhookResult.Outcome.PROCESSED);
        assertThat(paymentStatus()).isEqualTo("PAID");
        assertThat(count("outbox")).isEqualTo(1);
    }

    @Test
    @DisplayName("unknown session and uninteresting type are stored and answered 200, never applied")
    void unknownSessionAndUninterestingTypeAreIgnored() {
        byte[] unknown = paymentPayload("evt_unknown", "payment.succeeded", "sbx_nope", "pay_1", null);
        assertThat(processor.process(PaymentGatewayType.SANDBOX, unknown, signed(unknown)).outcome())
                .isEqualTo(WebhookResult.Outcome.IGNORED);

        openAttempt("sbx_boring");
        byte[] boring = paymentPayload("evt_boring", "invoice.updated", "sbx_boring", null, null);
        assertThat(processor.process(PaymentGatewayType.SANDBOX, boring, signed(boring)).outcome())
                .isEqualTo(WebhookResult.Outcome.IGNORED);

        assertThat(count("webhook_events")).isEqualTo(2);
        assertThat(count("outbox")).isEqualTo(0);
        assertThat(paymentStatus()).isEqualTo("PENDING");
    }

    @Test
    @DisplayName("refund vocabulary for an unknown refund is stored and IGNORED — never applied")
    void refundEventForUnknownRefundIsIgnored() {
        openAttempt("sbx_refund");
        byte[] body = """
                {"id":"evt_ref1","type":"refund.succeeded","sessionId":"sbx_refund","paymentId":"pay_1","refundId":"sbx_ref_1"}"""
                .getBytes(StandardCharsets.UTF_8);

        WebhookResult result = processor.process(PaymentGatewayType.SANDBOX, body, signed(body));

        // Correlated by gateway refund id against the ledger — no such row, so stored, 200, IGNORED.
        assertThat(result.outcome()).isEqualTo(WebhookResult.Outcome.IGNORED);
        assertThat(paymentStatus()).isEqualTo("PENDING");
        assertThat(attemptStatus()).isEqualTo("PENDING");
        assertThat(count("outbox")).isEqualTo(0);
        assertThat(count("refunds")).isEqualTo(0);
        assertThat(jdbc.queryForObject(
                "select processing_status from webhook_events where event_id = 'evt_ref1'",
                String.class)).isEqualTo("IGNORED");
    }

    @Test
    @DisplayName("a webhook's new trace is recorded on the evidence row and the outbox row")
    void storedWebhookRecordsItsTraceId() {
        openAttempt("sbx_trace");
        byte[] body = paymentPayload("evt_trace", "payment.succeeded", "sbx_trace", "pay_1", null);

        WebhookResult result = processor.process(PaymentGatewayType.SANDBOX, body, signed(body));

        // The @Observed on process() started a NEW trace (a webhook carries no traceparent by
        // design). Storing its id on the evidence row is what links any stored payload
        // back to exactly what it did — the "customer says I paid and nothing happened" path.
        assertThat(result.outcome()).isEqualTo(WebhookResult.Outcome.PROCESSED);
        String eventTraceId = jdbc.queryForObject(
                "select trace_id from webhook_events where event_id = 'evt_trace'", String.class);
        assertThat(eventTraceId).matches("[0-9a-f]{32}");
        // The same trace rides on the outbox row, so the relay can carry it to Booking and the
        // whole confirm→email chain joins this trace.
        assertThat(jdbc.queryForObject("select trace_id from outbox", String.class))
                .isEqualTo(eventTraceId);
    }

    @Test
    @DisplayName("the outbox row is visible on a separate connection once the business write commits")
    void outboxRowCommitsWithTheStateChange() throws SQLException {
        openAttempt("sbx_commit");
        byte[] body = paymentPayload("evt_commit", "payment.succeeded", "sbx_commit", "pay_1", null);
        processor.process(PaymentGatewayType.SANDBOX, body, signed(body));

        // A genuinely separate connection sees committed data only — finding the row here proves it
        // committed WITH the PAID write, not in some later or lost step.
        try (Connection other = separateConnection()) {
            assertThat(queryInt(other, "select count(*) from outbox")).isEqualTo(1);
            assertThat(queryString(other, "select status from payments")).isEqualTo("PAID");
        }
    }

    @Test
    @DisplayName("the outbox row rolls back with the business write — and the evidence survives")
    void outboxRowRollsBackWithTheStateChange() throws SQLException {
        openAttempt("sbx_rollback");
        byte[] body = paymentPayload("evt_rb", "payment.succeeded", "sbx_rollback", "pay_1", null);
        GatewayEvent event = gateway.parseAndVerifyWebhook(asString(body), signed(body));
        var stored = writer.storeVerifiedEvent(PaymentGatewayType.SANDBOX, event, body, signed(body));

        transactions.execute(status -> {
            writer.applyOutcome(PaymentGatewayType.SANDBOX, event, stored.getId());
            // Still inside the outer transaction: the business write and its outbox row must be
            // invisible to anyone else — they have not committed.
            try (Connection other = separateConnection()) {
                assertThat(queryInt(other, "select count(*) from outbox")).isEqualTo(0);
                assertThat(queryString(other, "select status from payments")).isEqualTo("PENDING");
            } catch (SQLException e) {
                throw new IllegalStateException(e);
            }
            status.setRollbackOnly(); // the business transaction fails after the apply
            return null;
        });

        // The rollback took the state change AND its outbox row — but the evidence row committed in
        // its own earlier transaction, so the redelivery that heals this is already possible.
        assertThat(paymentStatus()).isEqualTo("PENDING");
        assertThat(count("outbox")).isEqualTo(0);
        assertThat(jdbc.queryForObject(
                "select processing_status from webhook_events where event_id = 'evt_rb'",
                String.class)).isEqualTo("RECEIVED");
    }

    // ---------------------------------------------------------------------------
    // Fixtures.
    // ---------------------------------------------------------------------------

    private void openAttempt(String sessionId) {
        Payment payment = new Payment(1001L, USER, new BigDecimal("300.00"), "EGP");
        PaymentAttempt attempt = new PaymentAttempt(PaymentGatewayType.SANDBOX,
                "key-" + UUID.randomUUID());
        payment.addAttempt(attempt);
        attempt.recordSession(sessionId, "http://localhost/checkout/" + sessionId,
                Instant.now().plus(10, ChronoUnit.MINUTES));
        payments.save(payment);
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
        return Map.of(SIGNATURE_HEADER, gateway.sign(asString(body)));
    }

    private static String asString(byte[] body) {
        return new String(body, StandardCharsets.UTF_8);
    }

    private String paymentStatus() {
        return jdbc.queryForObject("select status from payments", String.class);
    }

    private String attemptStatus() {
        return jdbc.queryForObject("select status from payment_attempts", String.class);
    }

    private int count(String table) {
        return jdbc.queryForObject("select count(*) from " + table, Integer.class);
    }

    /** A connection outside any transaction manager — sees committed data only. */
    private Connection separateConnection() throws SQLException {
        return DriverManager.getConnection(dataSource.getJdbcUrl(), dataSource.getUsername(),
                dataSource.getPassword());
    }

    private int queryInt(Connection connection, String sql) throws SQLException {
        try (var statement = connection.prepareStatement(sql);
                var rows = statement.executeQuery()) {
            assertThat(rows.next()).isTrue();
            return rows.getInt(1);
        }
    }

    private String queryString(Connection connection, String sql) throws SQLException {
        try (var statement = connection.prepareStatement(sql);
                var rows = statement.executeQuery()) {
            assertThat(rows.next()).isTrue();
            return rows.getString(1);
        }
    }
}
