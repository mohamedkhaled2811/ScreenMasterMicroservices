package com.gr74.payment.gateway.sandbox;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gr74.payment.config.SandboxGatewayProps;
import com.gr74.payment.gateway.GatewayEvent;
import com.gr74.payment.gateway.GatewayEventKind;
import com.gr74.payment.gateway.GatewayException;
import com.gr74.payment.gateway.GatewayPaymentStatus;
import com.gr74.payment.gateway.GatewayRefundRequest;
import com.gr74.payment.gateway.GatewaySession;
import com.gr74.payment.gateway.GatewaySessionRequest;
import com.gr74.payment.gateway.GatewayStatusQuery;
import com.gr74.payment.gateway.PaymentGateway;
import com.gr74.payment.gateway.RefundResult;
import com.gr74.payment.gateway.WebhookSignatureException;
import com.gr74.payment.model.PaymentAttemptStatus;
import com.gr74.payment.model.PaymentGatewayType;
import com.gr74.payment.model.RefundStatus;
import com.gr74.payment.repository.SandboxChargeRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Controllable in-house gateway implementing the same port. Signs its own webhooks;
 * configurable failure rate, latency, and session TTL. Always registered.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SandboxGateway implements PaymentGateway {

    private static final String HMAC_ALGORITHM = "HmacSHA256";

    private final SandboxGatewayProps props;
    private final ObjectMapper objectMapper;
    /** Sandbox ledger; only this gateway and its checkout controller touch it. */
    private final SandboxChargeRepository charges;
    /** Delivers webhooks as signed HTTP through the front door, never in-process. */
    private final SandboxWebhookClient webhookClient;

    @Override
    public PaymentGatewayType type() {
        return PaymentGatewayType.SANDBOX;
    }

    /** Settles anything; fallback gateway with no currency restrictions. */
    @Override
    public Set<String> supportedCurrencies() {
        return props.supportedCurrencies();
    }

    @Override
    public GatewaySession createSession(GatewaySessionRequest request) {
        simulateLatency();

        // Simulate an unreachable gateway for the circuit breaker.
        if (draw() < props.unavailableRate()) {
            throw new GatewayException(type(), "simulated outage (unavailable-rate="
                    + props.unavailableRate() + ")");
        }

        String sessionId = "sbx_sess_" + UUID.randomUUID();
        Instant expiresAt = Instant.now().plus(props.sessionTtl());
        // Checkout page served by our own controller.
        String checkoutUrl = props.checkoutBaseUrl() + "/" + sessionId;

        log.info("SANDBOX session created sessionId={} attemptId={} amount={} {} expiresAt={}",
                sessionId, request.attemptId(), request.amount(), request.currency(), expiresAt);

        return new GatewaySession(sessionId, checkoutUrl, expiresAt);
    }

    /** Reports the outcome recorded at pay time, never a fresh draw; PENDING when never paid. */
    @Override
    public GatewayPaymentStatus fetchStatus(GatewayStatusQuery query) {
        simulateLatency();
        // Caller may hold either id; session id first.
        String sessionId = firstNonBlank(query.gatewaySessionId(), query.gatewayPaymentId());
        return charges.findBySessionId(sessionId)
                .or(() -> charges.findByGatewayPaymentId(sessionId))
                .map(charge -> new GatewayPaymentStatus(
                        charge.getOutcome(), charge.getGatewayPaymentId(), charge.getFailureReason()))
                .orElseGet(() -> new GatewayPaymentStatus(
                        PaymentAttemptStatus.PENDING, query.gatewayPaymentId(), null));
    }

    /** Accept a refund as PENDING, then confirm via signed refund webhook; only the webhook promotes it. */
    @Override
    public RefundResult refund(GatewayRefundRequest request) {
        simulateLatency();
        String refundId = "sbx_ref_" + UUID.randomUUID();
        log.info("SANDBOX refund accepted gatewayPaymentId={} amount={} {} reason={} refundId={}",
                request.gatewayPaymentId(), request.amount(), request.currency(), request.reason(),
                refundId);
        String payload = refundPayload(request.gatewayPaymentId(), refundId);
        webhookClient.deliver("sandbox", payload, sign(payload),
                SandboxCheckoutController.SIGNATURE_HEADER);
        return new RefundResult(RefundStatus.PENDING, refundId, null);
    }

    /** Signed body for the confirming refund webhook. */
    String refundPayload(String gatewayPaymentId, String refundId) {
        try {
            Map<String, Object> payload = new java.util.LinkedHashMap<>();
            payload.put("id", "evt_" + UUID.randomUUID());
            payload.put("type", "refund.succeeded");
            payload.put("paymentId", gatewayPaymentId);
            payload.put("refundId", refundId);
            return objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            throw new GatewayException(type(), "could not build refund webhook payload", e);
        }
    }

    /** Verify HMAC-SHA256 over the raw body (constant-time) and normalize. */
    @Override
    public GatewayEvent parseAndVerifyWebhook(String rawPayload, Map<String, String> headers) {
        String provided = headers.get("x-sandbox-signature");
        if (provided == null || provided.isBlank()) {
            throw new WebhookSignatureException(type(), "missing X-Sandbox-Signature header");
        }

        String expected = sign(rawPayload);
        // Constant-time comparison.
        if (!MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                provided.getBytes(StandardCharsets.UTF_8))) {
            throw new WebhookSignatureException(type(), "signature mismatch");
        }

        try {
            JsonNode root = objectMapper.readTree(rawPayload);
            String rawType = root.path("type").asText(null);
            String eventId = root.path("id").asText(null);
            String sessionId = root.path("sessionId").asText(null);
            String paymentId = root.path("paymentId").asText(null);
            // Refund vocabulary shares the pipe; only the terminal handler differs.
            if ("refund.succeeded".equals(rawType)) {
                return new GatewayEvent(eventId, rawType, sessionId, paymentId,
                        null, null, GatewayEventKind.REFUND,
                        RefundStatus.SUCCEEDED, root.path("refundId").asText(null));
            }
            if ("refund.failed".equals(rawType)) {
                return new GatewayEvent(eventId, rawType, sessionId, paymentId,
                        null, null, GatewayEventKind.REFUND,
                        RefundStatus.FAILED, root.path("refundId").asText(null));
            }
            return new GatewayEvent(
                    eventId,
                    rawType,
                    sessionId,
                    paymentId,
                    normalize(rawType),
                    root.path("failureReason").asText(null));
        } catch (Exception e) {
            throw new GatewayException(type(), "malformed webhook payload", e);
        }
    }

    /** Sign a body as outbound webhooks are signed. */
    public String sign(String rawPayload) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(props.webhookSecret().getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM));
            return HexFormat.of().formatHex(mac.doFinal(rawPayload.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new GatewayException(type(), "could not compute webhook signature", e);
        }
    }

    /** Map gateway vocabulary to ours; unknown types are not actionable. */
    private PaymentAttemptStatus normalize(String rawType) {
        if (rawType == null) {
            return null;
        }
        return switch (rawType) {
            case "payment.succeeded" -> PaymentAttemptStatus.SUCCEEDED;
            case "payment.failed" -> PaymentAttemptStatus.FAILED;
            case "payment.expired" -> PaymentAttemptStatus.EXPIRED;
            case "payment.cancelled" -> PaymentAttemptStatus.CANCELLED;
            default -> null; // stored and answered 200, but IGNORED
        };
    }

    /** Whether a checkout should succeed, per the configured failure rate. */
    public boolean shouldSucceed() {
        return draw() >= props.failureRate();
    }

    private static String firstNonBlank(String primary, String fallback) {
        if (primary != null && !primary.isBlank()) {
            return primary;
        }
        return fallback;
    }

    private double draw() {
        return ThreadLocalRandom.current().nextDouble();
    }

    private void simulateLatency() {
        if (props.latencyMillis() <= 0) {
            return;
        }
        try {
            Thread.sleep(props.latencyMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            // Interrupted thread is not a decline; fail loudly.
            throw new GatewayException(type(), "interrupted while simulating gateway latency", e);
        }
    }
}
