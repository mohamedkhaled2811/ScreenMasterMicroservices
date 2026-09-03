package com.gr74.payment.gateway.stripe;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gr74.payment.config.ConditionalOnGatewayCredentials;
import com.gr74.payment.config.StripeGatewayProps;
import com.gr74.payment.gateway.GatewayEvent;
import com.gr74.payment.gateway.GatewayException;
import com.gr74.payment.gateway.GatewayPaymentStatus;
import com.gr74.payment.gateway.GatewayRefundRequest;
import com.gr74.payment.gateway.GatewaySession;
import com.gr74.payment.gateway.GatewaySessionRequest;
import com.gr74.payment.gateway.MoneyConverter;
import com.gr74.payment.gateway.PaymentGateway;
import com.gr74.payment.gateway.RefundResult;
import com.gr74.payment.gateway.WebhookSignatureException;
import com.gr74.payment.model.PaymentAttemptStatus;
import com.gr74.payment.model.PaymentGatewayType;
import com.gr74.payment.model.RefundStatus;

import lombok.extern.slf4j.Slf4j;

/**
 * Stripe, via <b>hosted Checkout Sessions</b> — the card form lives on Stripe's page, never ours.
 *
 * <p>Registered only when {@code payment.gateway.stripe.secret-key} holds a non-blank value: with no
 * credentials the bean is conditioned out of the context, so it never appears in the
 * {@link com.gr74.payment.gateway.GatewayRegistry} and is never offered to a client. A misconfigured
 * gateway is absent, not broken. (The custom condition matters — plain {@code @ConditionalOnProperty}
 * counts an empty string as present; see {@link ConditionalOnGatewayCredentials}.)
 *
 * <p>Talks Stripe's REST API directly with a {@link RestClient} rather than pulling in the Stripe
 * SDK: the surface we need is three calls, and going direct keeps the adapter's translation work —
 * form-encoding, minor units, status vocabulary — visible instead of hidden behind a library. That is
 * the point of the exercise.
 *
 * <p><b>Test mode only.</b> {@link StripeGatewayProps} rejects a live {@code sk_live_} key outside the
 * {@code production} profile, so this project is structurally incapable of moving real money.
 */
@Slf4j
@Component
@ConditionalOnGatewayCredentials("payment.gateway.stripe.secret-key")
public class StripeGateway implements PaymentGateway {

    /** Stripe signs the raw body; the header carries a timestamp and one or more v1 signatures. */
    private static final String SIGNATURE_HEADER = "stripe-signature";
    private static final String HMAC_ALGORITHM = "HmacSHA256";

    private final StripeGatewayProps props;
    private final ObjectMapper objectMapper;
    private final RestClient client;

    public StripeGateway(StripeGatewayProps props, ObjectMapper objectMapper, RestClient.Builder builder) {
        this.props = props;
        this.objectMapper = objectMapper;
        this.client = builder
                .baseUrl(props.apiBaseUrl())
                .defaultHeader("Authorization", "Bearer " + props.secretKey())
                .build();
    }

    @Override
    public PaymentGatewayType type() {
        return PaymentGatewayType.STRIPE;
    }

    @Override
    public Set<String> supportedCurrencies() {
        return props.supportedCurrencies();
    }

    /**
     * Create a Checkout Session.
     *
     * <p>Two details that matter: the amount is converted to <b>minor units</b> here (Stripe wants
     * {@code 30050}, not {@code 300.50}) and never in the domain; and the idempotency key is sent as
     * Stripe's own {@code Idempotency-Key} header, which is what makes a retry after a timeout safe
     * rather than a second charge.
     */
    @Override
    public GatewaySession createSession(GatewaySessionRequest request) {
        long minorUnits = MoneyConverter.toMinorUnits(request.amount(), request.currency());
        String currency = request.currency().toLowerCase(); // Stripe wants lowercase ISO codes

        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("mode", "payment");
        form.add("success_url", request.returnUrl());
        form.add("cancel_url", request.cancelUrl());
        form.add("client_reference_id", String.valueOf(request.attemptId()));
        form.add("line_items[0][quantity]", "1");
        form.add("line_items[0][price_data][currency]", currency);
        form.add("line_items[0][price_data][unit_amount]", String.valueOf(minorUnits));
        form.add("line_items[0][price_data][product_data][name]",
                "ScreenMaster booking #" + request.bookingId());
        // Metadata is how a human reading Stripe's dashboard can tell what a charge was for, and how
        // reconciliation correlates a Stripe payment back to our rows.
        form.add("metadata[paymentId]", String.valueOf(request.paymentId()));
        form.add("metadata[attemptId]", String.valueOf(request.attemptId()));
        form.add("metadata[bookingId]", String.valueOf(request.bookingId()));

        JsonNode response = post("/v1/checkout/sessions", form, request.idempotencyKey());

        String sessionId = text(response, "id");
        String checkoutUrl = text(response, "url");
        if (sessionId == null || checkoutUrl == null) {
            throw new GatewayException(type(), "checkout session response missing id or url");
        }

        // Stripe returns expires_at as epoch seconds; fall back to our own TTL when absent.
        Instant expiresAt = response.hasNonNull("expires_at")
                ? Instant.ofEpochSecond(response.get("expires_at").asLong())
                : Instant.now().plus(props.sessionTtl());

        log.info("STRIPE session created sessionId={} attemptId={} amount={} {} expiresAt={}",
                sessionId, request.attemptId(), request.amount(), request.currency(), expiresAt);

        return new GatewaySession(sessionId, checkoutUrl, expiresAt);
    }

    @Override
    public GatewayPaymentStatus fetchStatus(String gatewayPaymentId) {
        JsonNode session = get("/v1/checkout/sessions/" + gatewayPaymentId);
        String paymentStatus = text(session, "payment_status");
        String sessionStatus = text(session, "status");
        String paymentIntent = text(session, "payment_intent");

        PaymentAttemptStatus status = normalizeSessionStatus(paymentStatus, sessionStatus);
        String failureReason = status == PaymentAttemptStatus.FAILED ? "stripe_status_" + sessionStatus : null;
        return new GatewayPaymentStatus(status, paymentIntent, failureReason);
    }

    @Override
    public RefundResult refund(GatewayRefundRequest request) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("payment_intent", request.gatewayPaymentId());
        form.add("amount", String.valueOf(MoneyConverter.toMinorUnits(request.amount(), request.currency())));
        form.add("metadata[reason]", request.reason());

        JsonNode response = post("/v1/refunds", form, request.idempotencyKey());
        String refundId = text(response, "id");
        String status = text(response, "status");

        // Stripe: succeeded | pending | failed | canceled. Only "succeeded" has moved money.
        RefundStatus normalized = switch (status == null ? "" : status) {
            case "succeeded" -> RefundStatus.SUCCEEDED;
            case "failed", "canceled" -> RefundStatus.FAILED;
            default -> RefundStatus.PENDING;
        };
        return new RefundResult(normalized, refundId,
                normalized == RefundStatus.FAILED ? "stripe_refund_" + status : null);
    }

    /**
     * Verify Stripe's {@code Stripe-Signature} header and normalize the event.
     *
     * <p>The signed payload is {@code "{timestamp}.{raw body}"}, HMAC-SHA256 with the endpoint's
     * webhook secret. We compare in constant time and, when a tolerance is configured, reject
     * timestamps outside it — that is what stops an attacker replaying a genuine, correctly-signed
     * delivery indefinitely.
     */
    @Override
    public GatewayEvent parseAndVerifyWebhook(String rawPayload, Map<String, String> headers) {
        String header = headers.get(SIGNATURE_HEADER);
        if (header == null || header.isBlank()) {
            throw new WebhookSignatureException(type(), "missing Stripe-Signature header");
        }

        String timestamp = null;
        boolean matched = false;
        for (String part : header.split(",")) {
            String[] kv = part.trim().split("=", 2);
            if (kv.length != 2) {
                continue;
            }
            if ("t".equals(kv[0])) {
                timestamp = kv[1];
            }
        }
        if (timestamp == null) {
            throw new WebhookSignatureException(type(), "signature header has no timestamp");
        }

        String expected = hmacHex(timestamp + "." + rawPayload, props.webhookSecret());
        for (String part : header.split(",")) {
            String[] kv = part.trim().split("=", 2);
            if (kv.length == 2 && "v1".equals(kv[0])
                    && MessageDigest.isEqual(
                            expected.getBytes(StandardCharsets.UTF_8),
                            kv[1].getBytes(StandardCharsets.UTF_8))) {
                matched = true;
                break;
            }
        }
        if (!matched) {
            throw new WebhookSignatureException(type(), "no v1 signature matched");
        }
        rejectIfStale(timestamp);

        try {
            JsonNode root = objectMapper.readTree(rawPayload);
            String rawType = text(root, "type");
            JsonNode object = root.path("data").path("object");
            return new GatewayEvent(
                    text(root, "id"),
                    rawType,
                    text(object, "id"),                 // the checkout session id
                    text(object, "payment_intent"),
                    normalizeEventType(rawType),
                    text(object, "failure_reason"));
        } catch (WebhookSignatureException e) {
            throw e;
        } catch (Exception e) {
            throw new GatewayException(type(), "malformed webhook payload", e);
        }
    }

    /** Stripe's event vocabulary → ours. Anything else is stored but not acted on. */
    private PaymentAttemptStatus normalizeEventType(String rawType) {
        if (rawType == null) {
            return null;
        }
        return switch (rawType) {
            case "checkout.session.completed", "checkout.session.async_payment_succeeded" ->
                    PaymentAttemptStatus.SUCCEEDED;
            case "checkout.session.async_payment_failed", "payment_intent.payment_failed" ->
                    PaymentAttemptStatus.FAILED;
            case "checkout.session.expired" -> PaymentAttemptStatus.EXPIRED;
            default -> null;
        };
    }

    private PaymentAttemptStatus normalizeSessionStatus(String paymentStatus, String sessionStatus) {
        if ("paid".equals(paymentStatus)) {
            return PaymentAttemptStatus.SUCCEEDED;
        }
        if ("expired".equals(sessionStatus)) {
            return PaymentAttemptStatus.EXPIRED;
        }
        if ("complete".equals(sessionStatus)) {
            return PaymentAttemptStatus.FAILED; // complete but unpaid
        }
        return PaymentAttemptStatus.PENDING;
    }

    private void rejectIfStale(String timestamp) {
        if (props.signatureToleranceSeconds() <= 0) {
            return;
        }
        try {
            long age = Math.abs(Instant.now().getEpochSecond() - Long.parseLong(timestamp));
            if (age > props.signatureToleranceSeconds()) {
                throw new WebhookSignatureException(type(), "timestamp outside tolerance (" + age + "s)");
            }
        } catch (NumberFormatException e) {
            throw new WebhookSignatureException(type(), "unparseable signature timestamp");
        }
    }

    private JsonNode post(String path, MultiValueMap<String, String> form, String idempotencyKey) {
        try {
            String body = client.post()
                    .uri(path)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    // Stripe's own idempotency mechanism: the reason a retry after a timeout is safe.
                    .header("Idempotency-Key", idempotencyKey)
                    .body(form)
                    .retrieve()
                    .body(String.class);
            return objectMapper.readTree(body);
        } catch (RestClientException e) {
            throw new GatewayException(type(), "POST " + path + " failed", e);
        } catch (Exception e) {
            throw new GatewayException(type(), "could not parse response from POST " + path, e);
        }
    }

    private JsonNode get(String path) {
        try {
            return objectMapper.readTree(client.get().uri(path).retrieve().body(String.class));
        } catch (RestClientException e) {
            throw new GatewayException(type(), "GET " + path + " failed", e);
        } catch (Exception e) {
            throw new GatewayException(type(), "could not parse response from GET " + path, e);
        }
    }

    private String hmacHex(String data, String secret) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM));
            return HexFormat.of().formatHex(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new GatewayException(type(), "could not compute signature", e);
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }
}
