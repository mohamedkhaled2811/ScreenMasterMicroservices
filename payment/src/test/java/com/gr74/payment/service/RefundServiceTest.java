package com.gr74.payment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gr74.payment.dto.RefundResponse;
import com.gr74.payment.exception.PaymentErrorCode;
import com.gr74.payment.exception.PaymentException;
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
import com.gr74.payment.messaging.BookingConfirmationRejectedEvent;
import com.gr74.payment.messaging.BookingConfirmationRejectedListener;
import com.gr74.payment.model.Payment;
import com.gr74.payment.model.PaymentAttempt;
import com.gr74.payment.model.PaymentAttemptStatus;
import com.gr74.payment.model.PaymentGatewayType;
import com.gr74.payment.model.RefundStatus;
import com.gr74.payment.repository.PaymentAttemptRepository;
import com.gr74.payment.repository.PaymentRepository;
import com.gr74.payment.repository.RefundRepository;
import com.gr74.payment.webhook.WebhookProcessor;

/**
 * Refund lifecycle: capacity invariant, webhook confirmation, and compensation end to end.
 */
@SpringBootTest
class RefundServiceTest {

    private static final String USER = "11111111-1111-1111-1111-111111111111";
    private static final String SIGNATURE_HEADER = "x-sandbox-signature";

    @Autowired private RefundService refundService;
    @Autowired private WebhookProcessor processor;
    @Autowired private SandboxGateway gateway;
    @Autowired private RefundWriter writer;
    @Autowired private PaymentRepository payments;
    @Autowired private PaymentAttemptRepository attempts;
    @Autowired private RefundRepository refunds;
    @Autowired private BookingConfirmationRejectedListener rejectionListener;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbc;

    /** The gateway's outbound delivery never leaves the suite — captured instead. */
    @MockitoBean private SandboxWebhookClient webhookClient;

    private long bookingSeq = 9000L;

    @BeforeEach
    void setUp() {
        jdbc.update("delete from outbox");
        jdbc.update("delete from webhook_events");
        jdbc.update("delete from refunds");
        jdbc.update("delete from payment_attempts");
        jdbc.update("delete from sandbox_charges");
        jdbc.update("delete from payments");
    }

    @Test
    @DisplayName("full refund: omitted amount means everything, webhook flips the payment to REFUNDED")
    void fullRefundConfirmedByWebhook() {
        Payment payment = paidPayment("1000.00", "EGP", "sbx_full", "sbx_pay_full");

        // Omitted amount = the full remaining capacity; omitted reason = CUSTOMER_REQUEST.
        RefundResponse response = refundService.requestRefund(payment.getId(), null, null, "key-full-1");

        assertThat(response.amount()).isEqualByComparingTo("1000.00");
        assertThat(response.status()).isEqualTo(RefundStatus.PENDING);
        assertThat(response.gatewayRefundId()).startsWith("sbx_ref_");
        assertThat(response.reason()).isEqualTo("CUSTOMER_REQUEST");
        // Provisional: the API answer moved no money.
        assertThat(paymentStatus(payment)).isEqualTo("PAID");

        deliverRefundWebhook(response.gatewayRefundId(), "sbx_pay_full");

        assertThat(paymentStatus(payment)).isEqualTo("REFUNDED");
        assertThat(refundedTotal(payment)).isEqualByComparingTo("1000.00");
    }

    @Test
    @DisplayName("partial refunds accumulate: 300 then 200 of 1000 leaves 500 refundable and PARTIALLY_REFUNDED")
    void partialRefundsAccumulate() {
        Payment payment = paidPayment("1000.00", "EGP", "sbx_part", "sbx_pay_part");

        RefundResponse first = refundService.requestRefund(
                payment.getId(), new BigDecimal("300.00"), "CUSTOMER_REQUEST", "key-part-1");
        RefundResponse second = refundService.requestRefund(
                payment.getId(), new BigDecimal("200.00"), "CUSTOMER_REQUEST", "key-part-2");

        assertThat(first.status()).isEqualTo(RefundStatus.PENDING);
        assertThat(second.status()).isEqualTo(RefundStatus.PENDING);
        // Nothing confirmed yet — the total only moves on webhooks.
        assertThat(refundedTotal(payment)).isEqualByComparingTo("0");

        deliverRefundWebhook(first.gatewayRefundId(), "sbx_pay_part");
        assertThat(paymentStatus(payment)).isEqualTo("PARTIALLY_REFUNDED");
        assertThat(refundedTotal(payment)).isEqualByComparingTo("300.00");

        deliverRefundWebhook(second.gatewayRefundId(), "sbx_pay_part");
        assertThat(paymentStatus(payment)).isEqualTo("PARTIALLY_REFUNDED");
        assertThat(refundedTotal(payment)).isEqualByComparingTo("500.00");

        // 500 still refundable — and 500.01 is not.
        RefundResponse third = refundService.requestRefund(
                payment.getId(), new BigDecimal("500.00"), "CUSTOMER_REQUEST", "key-part-3");
        assertThat(third.status()).isEqualTo(RefundStatus.PENDING);
        assertThatThrownBy(() -> refundService.requestRefund(
                        payment.getId(), new BigDecimal("500.01"), "CUSTOMER_REQUEST", "key-part-4"))
                .isInstanceOf(PaymentException.class)
                .extracting(e -> ((PaymentException) e).errorCode())
                .isEqualTo(PaymentErrorCode.PAYMENT_REFUND_EXCEEDS_REMAINING);
    }

    @Test
    @DisplayName("the invariant counts PENDING: an in-flight refund blocks a second one over capacity")
    void pendingRefundCountsTowardInvariant() {
        Payment payment = paidPayment("1000.00", "EGP", "sbx_inv", "sbx_pay_inv");

        refundService.requestRefund(payment.getId(), new BigDecimal("300.00"), "CUSTOMER_REQUEST", "key-inv-1");

        // 300 in flight (not yet webhook-confirmed) + 800 requested > 1000.
        assertThatThrownBy(() -> refundService.requestRefund(
                        payment.getId(), new BigDecimal("800.00"), "CUSTOMER_REQUEST", "key-inv-2"))
                .isInstanceOf(PaymentException.class)
                .extracting(e -> ((PaymentException) e).errorCode())
                .isEqualTo(PaymentErrorCode.PAYMENT_REFUND_EXCEEDS_REMAINING);

        // Exactly at capacity is fine.
        RefundResponse fitting = refundService.requestRefund(
                payment.getId(), new BigDecimal("700.00"), "CUSTOMER_REQUEST", "key-inv-3");
        assertThat(fitting.status()).isEqualTo(RefundStatus.PENDING);
    }

    @Test
    @DisplayName("refunding money that was never captured is 409 PAYMENT_NOT_REFUNDABLE")
    void uncapturedPaymentIsNotRefundable() {
        Payment pending = new Payment(bookingSeq++, USER, new BigDecimal("100.00"), "EGP");
        payments.save(pending);

        assertThatThrownBy(() -> refundService.requestRefund(
                        pending.getId(), null, "CUSTOMER_REQUEST", "key-nr-1"))
                .isInstanceOf(PaymentException.class)
                .extracting(e -> ((PaymentException) e).errorCode())
                .isEqualTo(PaymentErrorCode.PAYMENT_NOT_REFUNDABLE);
    }

    @Test
    @DisplayName("a non-positive amount is 400, and an unknown payment is 404")
    void amountAndPaymentValidation() {
        Payment payment = paidPayment("1000.00", "EGP", "sbx_val", "sbx_pay_val");

        assertThatThrownBy(() -> refundService.requestRefund(
                        payment.getId(), new BigDecimal("-5.00"), "CUSTOMER_REQUEST", "key-val-1"))
                .isInstanceOf(PaymentException.class)
                .extracting(e -> ((PaymentException) e).errorCode())
                .isEqualTo(PaymentErrorCode.PAYMENT_VALIDATION_ERROR);

        assertThatThrownBy(() -> refundService.requestRefund(
                        999999L, null, "CUSTOMER_REQUEST", "key-val-2"))
                .isInstanceOf(PaymentException.class)
                .extracting(e -> ((PaymentException) e).errorCode())
                .isEqualTo(PaymentErrorCode.PAYMENT_NOT_FOUND);
    }

    @Test
    @DisplayName("two concurrent full refunds serialize on the row lock: one wins, one gets 409")
    void concurrentRefundsSerialize() throws Exception {
        Payment payment = paidPayment("1000.00", "EGP", "sbx_race", "sbx_pay_race");

        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<RefundResponse> first = pool.submit(() -> {
                start.await();
                return refundService.requestRefund(
                        payment.getId(), null, "CUSTOMER_REQUEST", "key-race-1");
            });
            Future<RefundResponse> second = pool.submit(() -> {
                start.await();
                return refundService.requestRefund(
                        payment.getId(), null, "CUSTOMER_REQUEST", "key-race-2");
            });
            start.countDown();

            int successes = 0;
            AtomicReference<PaymentErrorCode> loserCode = new AtomicReference<>();
            for (Future<RefundResponse> future : List.of(first, second)) {
                try {
                    assertThat(future.get().status()).isEqualTo(RefundStatus.PENDING);
                    successes++;
                } catch (java.util.concurrent.ExecutionException e) {
                    assertThat(e.getCause()).isInstanceOf(PaymentException.class);
                    loserCode.set(((PaymentException) e.getCause()).errorCode());
                }
            }
            assertThat(successes).isEqualTo(1);
            assertThat(loserCode.get()).isEqualTo(PaymentErrorCode.PAYMENT_REFUND_EXCEEDS_REMAINING);
            assertThat(refunds.count()).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("the same idempotency key twice returns the same refund and calls the gateway once")
    void idempotentKeyReturnsSameRefund() {
        Payment payment = paidPayment("1000.00", "EGP", "sbx_idem", "sbx_pay_idem");

        RefundResponse first = refundService.requestRefund(
                payment.getId(), new BigDecimal("250.00"), "CUSTOMER_REQUEST", "key-idem-1");
        RefundResponse second = refundService.requestRefund(
                payment.getId(), new BigDecimal("250.00"), "CUSTOMER_REQUEST", "key-idem-1");

        assertThat(second.id()).isEqualTo(first.id());
        assertThat(refunds.count()).isEqualTo(1);
        verify(webhookClient, times(1)).deliver(eq("sandbox"), anyString(), anyString(), eq(SIGNATURE_HEADER));
    }

    @Test
    @DisplayName("a redelivered refund webhook (new event id, same refund) is a no-op on the total")
    void redeliveredRefundWebhookIsNoop() {
        Payment payment = paidPayment("1000.00", "EGP", "sbx_red", "sbx_pay_red");
        RefundResponse response = refundService.requestRefund(
                payment.getId(), new BigDecimal("400.00"), "CUSTOMER_REQUEST", "key-red-1");

        deliverRefundWebhook(response.gatewayRefundId(), "sbx_pay_red");
        assertThat(refundedTotal(payment)).isEqualByComparingTo("400.00");

        // A second, distinct delivery confirming the same refund (Paymob's twin callbacks do this
        // for real: one for the refund txn, one for the original reporting refunded).
        deliverRefundWebhook(response.gatewayRefundId(), "sbx_pay_red");
        assertThat(refundedTotal(payment)).isEqualByComparingTo("400.00");
        assertThat(paymentStatus(payment)).isEqualTo("PARTIALLY_REFUNDED");
    }

    @Test
    @DisplayName("a gateway failure marks the refund FAILED, propagates the 503, and frees the invariant")
    void gatewayFailureMarksRefundFailed() {
        Payment payment = paidPayment("1000.00", "EGP", "sbx_fail", "sbx_pay_fail");
        RefundService failingService = new RefundService(
                refunds, payments, attempts, writer, throwingRegistry());

        assertThatThrownBy(() -> failingService.requestRefund(
                        payment.getId(), new BigDecimal("100.00"), "CUSTOMER_REQUEST", "key-fail-1"))
                .isInstanceOf(PaymentException.class)
                .extracting(e -> ((PaymentException) e).errorCode())
                .isEqualTo(PaymentErrorCode.PAYMENT_GATEWAY_UNAVAILABLE);

        assertThat(jdbc.queryForObject(
                "select status from refunds where idempotency_key = 'key-fail-1'", String.class))
                .isEqualTo("FAILED");

        // FAILED rows constrain nothing: the full amount is still refundable under a fresh key.
        RefundResponse retry = refundService.requestRefund(
                payment.getId(), null, "CUSTOMER_REQUEST", "key-fail-2");
        assertThat(retry.amount()).isEqualByComparingTo("1000.00");
    }

    @Test
    @DisplayName("compensation end to end: rejection → auto-refund with the derived key → webhook finalizes it")
    void compensationLoopEndToEnd() {
        Payment payment = paidPayment("750.00", "EGP", "sbx_comp", "sbx_pay_comp");
        String eventId = UUID.randomUUID().toString();
        BookingConfirmationRejectedEvent event = new BookingConfirmationRejectedEvent(
                eventId, 4242L, "BK-4242", payment.getId(), "EXPIRED", USER, Instant.now());

        // Into the listener as the broker would deliver it (a Map, converted explicitly).
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = objectMapper.convertValue(event, Map.class);
        rejectionListener.onRejection(payload);

        // The auto-refund was recorded under the derived key, for the full remaining amount.
        String recorded = jdbc.queryForObject(
                "select status from refunds where idempotency_key = '" + keyFor(eventId) + "'",
                String.class);
        assertThat(recorded).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject(
                "select amount from refunds where idempotency_key = '" + keyFor(eventId) + "'",
                BigDecimal.class)).isEqualByComparingTo("750.00");
        assertThat(jdbc.queryForObject(
                "select reason from refunds where idempotency_key = '" + keyFor(eventId) + "'",
                String.class)).isEqualTo("BOOKING_EXPIRED");

        // Redelivering the same rejection collapses onto the existing row — one refund, one call.
        rejectionListener.onRejection(payload);
        assertThat(jdbc.queryForObject("select count(*) from refunds", Integer.class)).isEqualTo(1);
        verify(webhookClient, times(1)).deliver(eq("sandbox"), anyString(), anyString(), eq(SIGNATURE_HEADER));

        // The sandbox's refund webhook (captured from the mocked delivery) finalizes it.
        ArgumentCaptor<String> payloads = ArgumentCaptor.forClass(String.class);
        verify(webhookClient, times(1)).deliver(eq("sandbox"), payloads.capture(), anyString(),
                eq(SIGNATURE_HEADER));
        byte[] body = payloads.getValue().getBytes(StandardCharsets.UTF_8);
        processor.process(PaymentGatewayType.SANDBOX, body,
                Map.of(SIGNATURE_HEADER, gateway.sign(payloads.getValue())));

        assertThat(paymentStatus(payment)).isEqualTo("REFUNDED");
        assertThat(refundedTotal(payment)).isEqualByComparingTo("750.00");
    }

    // ---------------------------------------------------------------------------
    // Fixtures.
    // ---------------------------------------------------------------------------

    /** A captured payment: SUCCEEDED attempt with a gateway transaction id, payment PAID. */
    private Payment paidPayment(String amount, String currency, String sessionId, String gatewayPaymentId) {
        Payment payment = new Payment(bookingSeq++, USER, new BigDecimal(amount), currency);
        PaymentAttempt attempt = new PaymentAttempt(PaymentGatewayType.SANDBOX, "key-" + UUID.randomUUID());
        payment.addAttempt(attempt);
        attempt.recordSession(sessionId, "http://localhost/checkout/" + sessionId,
                Instant.now().plus(10, ChronoUnit.MINUTES));
        payments.saveAndFlush(payment);
        attempt.transitionTo(PaymentAttemptStatus.SUCCEEDED, gatewayPaymentId, null);
        payment.markPaid();
        payments.saveAndFlush(payment);
        return payment;
    }

    /**
     * Deliver a signed sandbox {@code refund.succeeded} webhook for a gateway refund id — what the
     * sandbox's HTTP delivery does in production and what the captured payload does in the
     * compensation test. Deliberately omits {@code sessionId}: like Stripe's
     * {@code charge.refunded}, correlation must work without it.
     */
    private void deliverRefundWebhook(String gatewayRefundId, String gatewayPaymentId) {
        String payload = """
                {"id":"evt_%s","type":"refund.succeeded","paymentId":"%s","refundId":"%s"}"""
                .formatted(UUID.randomUUID(), gatewayPaymentId, gatewayRefundId);
        byte[] body = payload.getBytes(StandardCharsets.UTF_8);
        var result = processor.process(PaymentGatewayType.SANDBOX, body,
                Map.of(SIGNATURE_HEADER, gateway.sign(payload)));
        assertThat(result.outcome().name()).isNotEqualTo("BAD_SIGNATURE");
    }

    /** A registry whose sandbox adapter always fails the refund call — the outage stand-in. */
    private GatewayRegistry throwingRegistry() {
        PaymentGateway failing = new PaymentGateway() {
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
                throw new UnsupportedOperationException();
            }

            @Override
            public RefundResult refund(GatewayRefundRequest request) {
                throw new GatewayException(PaymentGatewayType.SANDBOX, "simulated refund outage");
            }

            @Override
            public GatewayEvent parseAndVerifyWebhook(String rawPayload, Map<String, String> headers) {
                throw new UnsupportedOperationException();
            }
        };
        return new GatewayRegistry(List.of(failing));
    }

    private static String keyFor(String eventId) {
        return "reject-" + eventId;
    }

    private String paymentStatus(Payment payment) {
        return jdbc.queryForObject(
                "select status from payments where id = " + payment.getId(), String.class);
    }

    private BigDecimal refundedTotal(Payment payment) {
        return jdbc.queryForObject(
                "select refunded_amount from payments where id = " + payment.getId(), BigDecimal.class);
    }
}
