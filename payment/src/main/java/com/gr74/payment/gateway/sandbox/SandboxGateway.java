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
 * A gateway we happen to own.
 *
 * <p><b>This is not a test mock.</b> It implements the same {@link PaymentGateway} port as Stripe and
 * Paymob, is registered the same way, signs its own webhooks with a real HMAC, and honours a session
 * TTL. The difference is only that its failure rate and latency are <em>configurable</em>, which is
 * what makes the failure script (BUILD_PLAN 3.7) and the circuit-breaker demo (Phase 5) possible
 * without hammering a real sandbox or waiting on someone else's outage.
 *
 * <p>It is the successor to the old {@code FakePaymentProvider}'s {@code FAIL_RATE} knob — but where
 * that fake returned an approve/decline <em>synchronously from the charge call</em> (teaching a shape
 * no real gateway has), this one behaves like a real gateway: it opens a session, returns a checkout
 * URL, and reports the outcome later through a signed webhook. The controllable failure survived; the
 * misleading shape did not.
 *
 * <p>Always registered — it has no external credentials to be missing — so there is always at least
 * one usable gateway in any deployment.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SandboxGateway implements PaymentGateway {

    private static final String HMAC_ALGORITHM = "HmacSHA256";

    private final SandboxGatewayProps props;
    private final ObjectMapper objectMapper;
    /**
     * The sandbox's own ledger — the fake third party's memory, parked in payment-db for the lab.
     * Payment domain code never reads this; only this gateway ({@link #fetchStatus}) and its
     * checkout controller (recording the drawn outcome) touch it.
     */
    private final SandboxChargeRepository charges;
    /**
     * How this gateway "calls back": signed HTTP through the front door, never an in-process
     * call — so the refund path below exercises the same evidence store, dedupe, and correlation
     * as Stripe's {@code charge.refunded}.
     */
    private final SandboxWebhookClient webhookClient;

    @Override
    public PaymentGatewayType type() {
        return PaymentGatewayType.SANDBOX;
    }

    /**
     * Settles anything. It is our own gateway, so it has no real-world currency restrictions — which
     * also makes it the fallback that can always take a booking whatever its currency.
     */
    @Override
    public Set<String> supportedCurrencies() {
        return props.supportedCurrencies();
    }

    @Override
    public GatewaySession createSession(GatewaySessionRequest request) {
        simulateLatency();

        // Simulate an unreachable gateway, so the circuit breaker (Phase 5) has something to bite on.
        if (draw() < props.unavailableRate()) {
            throw new GatewayException(type(), "simulated outage (unavailable-rate="
                    + props.unavailableRate() + ")");
        }

        String sessionId = "sbx_sess_" + UUID.randomUUID();
        Instant expiresAt = Instant.now().plus(props.sessionTtl());
        // The checkout "page" is served by our own controller so the flow is clickable end to end.
        String checkoutUrl = props.checkoutBaseUrl() + "/" + sessionId;

        log.info("SANDBOX session created sessionId={} attemptId={} amount={} {} expiresAt={}",
                sessionId, request.attemptId(), request.amount(), request.currency(), expiresAt);

        return new GatewaySession(sessionId, checkoutUrl, expiresAt);
    }

    /**
     * Reconciliation's view: the outcome <b>recorded</b> when the user paid — never a fresh draw.
     *
     * <p>An earlier version drew against the failure rate on every call, so asking twice about the
     * same payment could give two different answers, and reconciliation could confirm a booking that
     * was never paid for. The failure-rate knob now applies exactly once, at pay time (see
     * {@link #shouldSucceed()} and the checkout controller that records its answer); this method
     * only reports what was recorded, or {@code PENDING} when the user never paid.
     */
    @Override
    public GatewayPaymentStatus fetchStatus(GatewayStatusQuery query) {
        simulateLatency();
        // Both ids identify the same ledger row; the caller may hold either. Session id first —
        // it is the id every attempt carries — then the payment id a webhook may have reported.
        String sessionId = firstNonBlank(query.gatewaySessionId(), query.gatewayPaymentId());
        return charges.findBySessionId(sessionId)
                .or(() -> charges.findByGatewayPaymentId(sessionId))
                .map(charge -> new GatewayPaymentStatus(
                        charge.getOutcome(), charge.getGatewayPaymentId(), charge.getFailureReason()))
                .orElseGet(() -> new GatewayPaymentStatus(
                        PaymentAttemptStatus.PENDING, query.gatewayPaymentId(), null));
    }

    /**
     * Ask the sandbox to return money. Provisional by design: returns {@code PENDING} with the
     * gateway's refund id, then delivers a signed {@code refund.succeeded} webhook through the
     * front door — and only that webhook promotes the refund to {@code SUCCEEDED} (see
     * {@code PaymentWriter.markRefundReceived}). A synchronous "succeeded" answer here would be
     * the old fake-provider shape the checkout path deliberately unlearned.
     *
     * <p>No separate sandbox refund ledger is kept: unlike payments, refunds have no
     * poll-for-status port method, so nothing would ever read such a table. The record of the
     * refund is the {@code refunds} row tx 2 writes; the webhook (or its redelivery) is the
     * confirmation. A failed delivery is exactly the network partition the payment path already
     * models — loud log, no throw, the refund simply stays PENDING.
     */
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

    /** The signed body the confirming refund webhook carries — also what tests feed back manually. */
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

    /**
     * Verifies a real HMAC-SHA256 over the raw body — the same mechanism and the same constant-time
     * comparison the real adapters use, so the webhook-security path is exercised by the hermetic
     * tests and the local demo, not only against a live gateway.
     */
    @Override
    public GatewayEvent parseAndVerifyWebhook(String rawPayload, Map<String, String> headers) {
        String provided = headers.get("x-sandbox-signature");
        if (provided == null || provided.isBlank()) {
            throw new WebhookSignatureException(type(), "missing X-Sandbox-Signature header");
        }

        String expected = sign(rawPayload);
        // Constant-time: String.equals leaks the signature byte by byte through timing.
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
            // The refund vocabulary shares the pipe, the evidence store, and the dedupe — only the
            // terminal handler differs (the refund ledger, correlated by refund id).
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

    /** Sign a body exactly as this gateway's outbound webhooks are signed. */
    public String sign(String rawPayload) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(props.webhookSecret().getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM));
            return HexFormat.of().formatHex(mac.doFinal(rawPayload.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new GatewayException(type(), "could not compute webhook signature", e);
        }
    }

    /** Map this gateway's vocabulary into ours; unknown types are not actionable. */
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

    /** Whether a checkout at this gateway should succeed, per the configured failure rate. */
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
            // An interrupted thread is not a payment decline — fail loudly rather than report one.
            throw new GatewayException(type(), "interrupted while simulating gateway latency", e);
        }
    }
}
