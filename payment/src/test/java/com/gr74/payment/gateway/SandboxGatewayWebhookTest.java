package com.gr74.payment.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import static org.mockito.Mockito.mock;

import java.time.Duration;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gr74.payment.config.SandboxGatewayProps;
import com.gr74.payment.gateway.sandbox.SandboxGateway;
import com.gr74.payment.model.PaymentAttemptStatus;
import com.gr74.payment.model.RefundStatus;
import com.gr74.payment.repository.SandboxChargeRepository;

/**
 * Webhook signature verification and status normalization.
 *
 * <p>Exercised through {@link SandboxGateway} because it signs with a real HMAC over the raw body —
 * the same mechanism Stripe uses — so these assertions cover the security-critical path without
 * reaching a live gateway. The Stripe and Paymob adapters differ only in <em>what</em> gets signed
 * (raw body vs a concatenated field list), not in the guarantee being tested here.
 */
class SandboxGatewayWebhookTest {

    private static final String SECRET = "test-webhook-secret";
    private static final String SIGNATURE_HEADER = "x-sandbox-signature";

    private SandboxGateway gateway;

    @BeforeEach
    void setUp() {
        gateway = new SandboxGateway(
                new SandboxGatewayProps(Set.of("EGP", "USD"), 0.0, 0.0, 0L,
                        Duration.ofMinutes(10), "http://localhost/checkout", SECRET),
                new ObjectMapper(),
                // fetchStatus's ledger is irrelevant here — signature and vocabulary are.
                mock(SandboxChargeRepository.class),
                mock(com.gr74.payment.gateway.sandbox.SandboxWebhookClient.class));
    }

    private Map<String, String> signed(String payload) {
        return Map.of(SIGNATURE_HEADER, gateway.sign(payload));
    }

    @Test
    @DisplayName("accepts a correctly signed webhook and normalizes it")
    void acceptsValidSignature() {
        String payload = """
                {"id":"evt_1","type":"payment.succeeded","sessionId":"sbx_sess_1","paymentId":"pay_1"}""";

        GatewayEvent event = gateway.parseAndVerifyWebhook(payload, signed(payload));

        assertThat(event.eventId()).isEqualTo("evt_1");
        assertThat(event.gatewaySessionId()).isEqualTo("sbx_sess_1");
        assertThat(event.status()).isEqualTo(PaymentAttemptStatus.SUCCEEDED);
        assertThat(event.isActionable()).isTrue();
    }

    @Test
    @DisplayName("rejects a body altered after signing")
    void rejectsTamperedPayload() {
        String original = """
                {"id":"evt_1","type":"payment.failed","sessionId":"sbx_sess_1"}""";
        // An attacker flips "failed" to "succeeded" but cannot recompute the HMAC without the secret.
        String tampered = original.replace("payment.failed", "payment.succeeded");

        assertThatThrownBy(() -> gateway.parseAndVerifyWebhook(tampered, signed(original)))
                .isInstanceOf(WebhookSignatureException.class);
    }

    @Test
    @DisplayName("rejects a single flipped byte in the signature")
    void rejectsFlippedByte() {
        String payload = """
                {"id":"evt_1","type":"payment.succeeded","sessionId":"sbx_sess_1"}""";
        String good = gateway.sign(payload);
        // Flip the last hex character — the smallest possible difference.
        String bad = good.substring(0, good.length() - 1) + (good.endsWith("0") ? "1" : "0");

        assertThatThrownBy(() -> gateway.parseAndVerifyWebhook(payload, Map.of(SIGNATURE_HEADER, bad)))
                .isInstanceOf(WebhookSignatureException.class);
    }

    @Test
    @DisplayName("rejects an unsigned webhook outright")
    void rejectsMissingSignature() {
        String payload = """
                {"id":"evt_1","type":"payment.succeeded","sessionId":"s1"}""";

        assertThatThrownBy(() -> gateway.parseAndVerifyWebhook(payload, Map.of()))
                .isInstanceOf(WebhookSignatureException.class)
                .hasMessageContaining("missing");
    }

    @Test
    @DisplayName("normalizes the gateway's vocabulary into ours")
    void normalizesStatuses() {
        assertThat(parseType("payment.succeeded")).isEqualTo(PaymentAttemptStatus.SUCCEEDED);
        assertThat(parseType("payment.failed")).isEqualTo(PaymentAttemptStatus.FAILED);
        assertThat(parseType("payment.expired")).isEqualTo(PaymentAttemptStatus.EXPIRED);
        assertThat(parseType("payment.cancelled")).isEqualTo(PaymentAttemptStatus.CANCELLED);
    }

    @Test
    @DisplayName("an event type we do not act on is parsed but not actionable")
    void unknownTypeIsNotActionable() {
        // Gateways emit far more event types than we care about. Such a delivery is still verified and
        // stored — and still answered 200 — but changes no state.
        GatewayEvent event = parse("""
                {"id":"evt_9","type":"invoice.updated","sessionId":"s1"}""");

        assertThat(event.status()).isNull();
        assertThat(event.isActionable()).isFalse();
    }

    @Test
    @DisplayName("refund.succeeded parses as a REFUND event carrying the gateway refund id")
    void refundSucceededIsARefundEvent() {
        GatewayEvent event = parse("""
                {"id":"evt_r1","type":"refund.succeeded","sessionId":"s1","paymentId":"pay_1","refundId":"sbx_ref_1"}""");

        assertThat(event.isRefund()).isTrue();
        assertThat(event.isActionable()).isFalse();
        assertThat(event.refundStatus()).isEqualTo(RefundStatus.SUCCEEDED);
        assertThat(event.gatewayRefundId()).isEqualTo("sbx_ref_1");
    }

    @Test
    @DisplayName("refund.failed parses as a REFUND event")
    void refundFailedIsARefundEvent() {
        GatewayEvent event = parse("""
                {"id":"evt_r2","type":"refund.failed","sessionId":"s1","paymentId":"pay_1","refundId":"sbx_ref_2"}""");

        assertThat(event.isRefund()).isTrue();
        assertThat(event.refundStatus()).isEqualTo(RefundStatus.FAILED);
    }

    private PaymentAttemptStatus parseType(String type) {
        return parse("{\"id\":\"evt_x\",\"type\":\"" + type + "\",\"sessionId\":\"s1\"}").status();
    }

    private GatewayEvent parse(String payload) {
        return gateway.parseAndVerifyWebhook(payload, signed(payload));
    }
}
