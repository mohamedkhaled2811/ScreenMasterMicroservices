package com.gr74.payment.gateway.paymob;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gr74.payment.config.ConditionalOnGatewayCredentials;
import com.gr74.payment.config.PaymobGatewayProps;
import com.gr74.payment.gateway.GatewayEvent;
import com.gr74.payment.gateway.GatewayEventKind;
import com.gr74.payment.gateway.GatewayException;
import com.gr74.payment.gateway.GatewayPaymentStatus;
import com.gr74.payment.gateway.GatewayRefundRequest;
import com.gr74.payment.gateway.GatewaySession;
import com.gr74.payment.gateway.GatewaySessionRequest;
import com.gr74.payment.gateway.GatewayStatusQuery;
import com.gr74.payment.gateway.MoneyConverter;
import com.gr74.payment.gateway.PaymentGateway;
import com.gr74.payment.gateway.RefundResult;
import com.gr74.payment.gateway.WebhookSignatureException;
import com.gr74.payment.model.PaymentAttemptStatus;
import com.gr74.payment.model.PaymentGatewayType;
import com.gr74.payment.model.RefundStatus;

import lombok.extern.slf4j.Slf4j;

/**
 * Paymob sandbox via hosted iframe checkout. Registered only when its API key is configured.
 * Three-step session (auth token, order, payment key); HMAC over a fixed field list via ?hmac query param.
 */
@Slf4j
@Component
@ConditionalOnGatewayCredentials("payment.gateway.paymob.api-key")
public class PaymobGateway implements PaymentGateway {

    private static final String HMAC_ALGORITHM = "HmacSHA512";

    /** Fixed Paymob-signed fields in order; must not be reordered. */
    private static final String[] HMAC_FIELDS = {
            "amount_cents", "created_at", "currency", "error_occured", "has_parent_transaction",
            "id", "integration_id", "is_3d_secure", "is_auth", "is_capture", "is_refunded",
            "is_standalone_payment", "is_voided", "order.id", "owner", "pending",
            "source_data.pan", "source_data.sub_type", "source_data.type", "success"
    };

    private final PaymobGatewayProps props;
    private final ObjectMapper objectMapper;
    private final RestClient client;

    public PaymobGateway(PaymobGatewayProps props, ObjectMapper objectMapper, RestClient.Builder builder) {
        this.props = props;
        this.objectMapper = objectMapper;
        this.client = builder.baseUrl(props.apiBaseUrl()).build();
    }

    @Override
    public PaymentGatewayType type() {
        return PaymentGatewayType.PAYMOB;
    }

    @Override
    public Set<String> supportedCurrencies() {
        return props.supportedCurrencies();
    }

    @Override
    public GatewaySession createSession(GatewaySessionRequest request) {
        long amountCents = MoneyConverter.toMinorUnits(request.amount(), request.currency());

        // 1. Authenticate for a short-lived token.
        String authToken = text(post("/api/auth/tokens", Map.of("api_key", props.apiKey())), "token");
        if (authToken == null) {
            throw new GatewayException(type(), "authentication returned no token");
        }

        // 2. Register order; merchant_order_id carries our idempotency key.
        Map<String, Object> orderBody = new LinkedHashMap<>();
        orderBody.put("auth_token", authToken);
        orderBody.put("delivery_needed", false);
        orderBody.put("amount_cents", amountCents);
        orderBody.put("currency", request.currency());
        orderBody.put("merchant_order_id", request.idempotencyKey());
        orderBody.put("items", java.util.List.of());
        JsonNode order = post("/api/ecommerce/orders", orderBody);
        String orderId = text(order, "id");
        if (orderId == null) {
            throw new GatewayException(type(), "order registration returned no id");
        }

        // 3. Request payment key for the hosted iframe.
        Map<String, Object> keyBody = new LinkedHashMap<>();
        keyBody.put("auth_token", authToken);
        keyBody.put("amount_cents", amountCents);
        keyBody.put("expiration", props.sessionTtl().toSeconds());
        keyBody.put("order_id", orderId);
        keyBody.put("currency", request.currency());
        keyBody.put("integration_id", props.integrationId());
        keyBody.put("billing_data", billingData(request));
        String paymentKey = text(post("/api/acceptance/payment_keys", keyBody), "token");
        if (paymentKey == null) {
            throw new GatewayException(type(), "payment key request returned no token");
        }

        String checkoutUrl = props.iframeBaseUrl() + "/" + props.iframeId() + "?payment_token=" + paymentKey;
        Instant expiresAt = Instant.now().plus(props.sessionTtl());

        log.info("PAYMOB session created orderId={} attemptId={} amountCents={} {} expiresAt={}",
                orderId, request.attemptId(), amountCents, request.currency(), expiresAt);

        // Order id is the stable handle webhooks carry.
        return new GatewaySession(orderId, checkoutUrl, expiresAt);
    }

    /** Status lookup keyed by transaction id, falling back to the order id. */
    @Override
    public GatewayPaymentStatus fetchStatus(GatewayStatusQuery query) {
        String transactionId = query.gatewayPaymentId() != null && !query.gatewayPaymentId().isBlank()
                ? query.gatewayPaymentId()
                : query.gatewaySessionId();
        String authToken = text(post("/api/auth/tokens", Map.of("api_key", props.apiKey())), "token");
        JsonNode transaction = getWithToken("/api/acceptance/transactions/" + transactionId, authToken);

        boolean success = transaction.path("success").asBoolean(false);
        boolean pending = transaction.path("pending").asBoolean(false);
        String txnId = text(transaction, "id");

        if (pending) {
            return new GatewayPaymentStatus(PaymentAttemptStatus.PENDING, txnId, null);
        }
        return success
                ? new GatewayPaymentStatus(PaymentAttemptStatus.SUCCEEDED, txnId, null)
                : new GatewayPaymentStatus(PaymentAttemptStatus.FAILED, txnId,
                        text(transaction.path("data"), "message"));
    }

    @Override
    public RefundResult refund(GatewayRefundRequest request) {
        String authToken = text(post("/api/auth/tokens", Map.of("api_key", props.apiKey())), "token");

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("auth_token", authToken);
        body.put("transaction_id", request.gatewayPaymentId());
        body.put("amount_cents", MoneyConverter.toMinorUnits(request.amount(), request.currency()));

        JsonNode response = post("/api/acceptance/void_refund/refund", body);
        boolean success = response.path("success").asBoolean(false);
        boolean pending = response.path("pending").asBoolean(false);
        String refundId = text(response, "id");

        if (pending) {
            return new RefundResult(RefundStatus.PENDING, refundId, null);
        }
        return success
                ? new RefundResult(RefundStatus.SUCCEEDED, refundId, null)
                : new RefundResult(RefundStatus.FAILED, refundId, "paymob_refund_rejected");
    }

    /** Verify HMAC (fixed field list, ?hmac param) and normalize the callback. */
    @Override
    public GatewayEvent parseAndVerifyWebhook(String rawPayload, Map<String, String> headers) {
        String provided = headers.get("hmac");
        if (provided == null || provided.isBlank()) {
            throw new WebhookSignatureException(type(), "missing hmac");
        }

        JsonNode root;
        try {
            root = objectMapper.readTree(rawPayload);
        } catch (Exception e) {
            throw new GatewayException(type(), "malformed webhook payload", e);
        }

        JsonNode obj = root.has("obj") ? root.get("obj") : root;
        String expected = hmacHex(concatenateSignedFields(obj), props.hmacSecret());

        // Constant-time comparison, case-insensitively normalized (Paymob sends lowercase hex).
        if (!MessageDigest.isEqual(
                expected.toLowerCase().getBytes(StandardCharsets.UTF_8),
                provided.toLowerCase().getBytes(StandardCharsets.UTF_8))) {
            throw new WebhookSignatureException(type(), "hmac mismatch");
        }

        boolean success = obj.path("success").asBoolean(false);
        boolean pending = obj.path("pending").asBoolean(false);
        boolean refunded = obj.path("is_refunded").asBoolean(false);
        boolean isRefundTxn = obj.path("is_refund").asBoolean(false)
                || (text(root, "type") != null && text(root, "type").toLowerCase().contains("refund"));

        if (isRefundTxn) {
            RefundStatus refundStatus = pending ? null
                    : success ? RefundStatus.SUCCEEDED : RefundStatus.FAILED;
            return new GatewayEvent(
                    text(obj, "id"),
                    text(root, "type"),
                    text(obj.path("order"), "id"),
                    text(obj, "id"),
                    null,
                    refundStatus == RefundStatus.FAILED ? text(obj.path("data"), "message") : null,
                    GatewayEventKind.REFUND,
                    refundStatus,
                    text(obj, "id"));
        }
        if (refunded) {
            return new GatewayEvent(
                    text(obj, "id"),
                    text(root, "type"),
                    text(obj.path("order"), "id"),
                    text(obj, "id"),
                    null,
                    null,
                    GatewayEventKind.REFUND,
                    RefundStatus.SUCCEEDED,
                    null); // the original txn's callback carries no refund id; 3.5 correlates by txn
        }

        PaymentAttemptStatus status;
        if (pending) {
            status = null;                                  // not yet an outcome: store and IGNORE
        } else {
            status = success ? PaymentAttemptStatus.SUCCEEDED : PaymentAttemptStatus.FAILED;
        }

        return new GatewayEvent(
                text(obj, "id"),                            // transaction id doubles as the event id
                text(root, "type"),
                text(obj.path("order"), "id"),              // matches the order id we stored
                text(obj, "id"),
                status,
                status == PaymentAttemptStatus.FAILED ? text(obj.path("data"), "message") : null);
    }

    /** Rebuild the signed string: fixed fields concatenated in order. */
    private String concatenateSignedFields(JsonNode obj) {
        StringBuilder sb = new StringBuilder();
        for (String field : HMAC_FIELDS) {
            JsonNode node = obj;
            for (String segment : field.split("\\.")) { // e.g. "order.id", "source_data.pan"
                node = node.path(segment);
            }
            sb.append(node.isMissingNode() || node.isNull() ? "" : node.asText());
        }
        return sb.toString();
    }

    /** Required billing block; uses documented filler values. */
    private Map<String, Object> billingData(GatewaySessionRequest request) {
        Map<String, Object> billing = new LinkedHashMap<>();
        for (String field : new String[]{"apartment", "floor", "street", "building", "shipping_method",
                "postal_code", "city", "country", "state"}) {
            billing.put(field, "NA");
        }
        billing.put("email", "noreply@screenmaster.local");
        billing.put("phone_number", "+20000000000");
        billing.put("first_name", "ScreenMaster");
        billing.put("last_name", "Customer");
        return billing;
    }

    private JsonNode post(String path, Map<String, ?> body) {
        try {
            return objectMapper.readTree(client.post()
                    .uri(path)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(String.class));
        } catch (RestClientException e) {
            throw new GatewayException(type(), "POST " + path + " failed", e);
        } catch (Exception e) {
            throw new GatewayException(type(), "could not parse response from POST " + path, e);
        }
    }

    private JsonNode getWithToken(String path, String authToken) {
        try {
            return objectMapper.readTree(client.get()
                    .uri(path)
                    .header("Authorization", "Bearer " + authToken)
                    .retrieve()
                    .body(String.class));
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
            throw new GatewayException(type(), "could not compute HMAC", e);
        }
    }

    private static String  text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }
}
