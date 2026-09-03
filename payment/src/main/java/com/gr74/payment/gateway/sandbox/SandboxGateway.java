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
import com.gr74.payment.gateway.GatewayException;
import com.gr74.payment.gateway.GatewayPaymentStatus;
import com.gr74.payment.gateway.GatewayRefundRequest;
import com.gr74.payment.gateway.GatewaySession;
import com.gr74.payment.gateway.GatewaySessionRequest;
import com.gr74.payment.gateway.PaymentGateway;
import com.gr74.payment.gateway.RefundResult;
import com.gr74.payment.gateway.WebhookSignatureException;
import com.gr74.payment.model.PaymentAttemptStatus;
import com.gr74.payment.model.PaymentGatewayType;
import com.gr74.payment.model.RefundStatus;

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
     * Reconciliation's view. Draws against the configured failure rate so a reconciliation run over
     * stranded attempts produces a realistic mix of outcomes rather than a uniform success.
     */
    @Override
    public GatewayPaymentStatus fetchStatus(String gatewayPaymentId) {
        simulateLatency();
        boolean failed = draw() < props.failureRate();
        return failed
                ? new GatewayPaymentStatus(PaymentAttemptStatus.FAILED, gatewayPaymentId, "simulated_decline")
                : new GatewayPaymentStatus(PaymentAttemptStatus.SUCCEEDED, gatewayPaymentId, null);
    }

    @Override
    public RefundResult refund(GatewayRefundRequest request) {
        simulateLatency();
        log.info("SANDBOX refund gatewayPaymentId={} amount={} {} reason={}",
                request.gatewayPaymentId(), request.amount(), request.currency(), request.reason());
        // Confirmed immediately: this gateway is the one place we can be sure, and it keeps the
        // compensation demo (paid-after-expiry -> auto-refund) observable without a webhook round-trip.
        return new RefundResult(RefundStatus.SUCCEEDED, "sbx_ref_" + UUID.randomUUID(), null);
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
            return new GatewayEvent(
                    root.path("id").asText(null),
                    rawType,
                    root.path("sessionId").asText(null),
                    root.path("paymentId").asText(null),
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
