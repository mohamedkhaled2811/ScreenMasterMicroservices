package com.gr74.payment.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gr74.payment.config.PaymobGatewayProps;
import com.gr74.payment.gateway.paymob.PaymobGateway;
import com.gr74.payment.model.PaymentAttemptStatus;

/**
 * Paymob webhook verification: concatenated-field HMAC delivered as a query parameter.
 */
class PaymobGatewayWebhookTest {

    private static final String SECRET = "test-paymob-hmac-secret";

    /**
     * The signed field order, duplicated from the adapter on purpose: if someone "tidies" the
     * production array, this copy disagrees and the test fails — which is the alarm we want.
     */
    private static final String[] HMAC_FIELDS = {
            "amount_cents", "created_at", "currency", "error_occured", "has_parent_transaction",
            "id", "integration_id", "is_3d_secure", "is_auth", "is_capture", "is_refunded",
            "is_standalone_payment", "is_voided", "order.id", "owner", "pending",
            "source_data.pan", "source_data.sub_type", "source_data.type", "success"
    };

    private PaymobGateway gateway;

    @BeforeEach
    void setUp() {
        // Deep stub: the constructor chains builder.baseUrl(..).build(); no HTTP happens in these tests.
        RestClient.Builder builder = mock(RestClient.Builder.class, RETURNS_DEEP_STUBS);
        when(builder.baseUrl(anyString())).thenReturn(builder);

        gateway = new PaymobGateway(
                new PaymobGatewayProps("test-api-key", SECRET, "integration-1", "iframe-1",
                        "http://localhost/paymob", "http://localhost/iframes",
                        Set.of("EGP"), Duration.ofMinutes(15)),
                new ObjectMapper(),
                builder);
    }

    /** A transaction callback carrying every signed field, so the concatenation is fully exercised. */
    private static String payload(long amountCents, boolean success) {
        return """
                {"type":"TRANSACTION","obj":{
                  "id":"txn_1","amount_cents":%d,"created_at":"2026-09-09T10:00:00Z","currency":"EGP",
                  "error_occured":false,"has_parent_transaction":false,"integration_id":"integration-1",
                  "is_3d_secure":true,"is_auth":false,"is_capture":false,"is_refunded":false,
                  "is_standalone_payment":true,"is_voided":false,"owner":"owner_1","pending":false,
                  "success":%s,"order":{"id":"order_1"},
                  "source_data":{"pan":"2346","sub_type":"MasterCard","type":"card"},
                  "data":{"message":"declined"}}}"""
                .formatted(amountCents, success);
    }

    /** Rebuild Paymob's signature the way Paymob does, independently of the adapter's private helper. */
    private static String hmacFor(String rawPayload) {
        try {
            com.fasterxml.jackson.databind.JsonNode obj =
                    new ObjectMapper().readTree(rawPayload).get("obj");
            StringBuilder sb = new StringBuilder();
            for (String field : HMAC_FIELDS) {
                com.fasterxml.jackson.databind.JsonNode node = obj;
                for (String segment : field.split("\\.")) {
                    node = node.path(segment);
                }
                sb.append(node.isMissingNode() || node.isNull() ? "" : node.asText());
            }
            Mac mac = Mac.getInstance("HmacSHA512");
            mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA512"));
            return HexFormat.of().formatHex(mac.doFinal(sb.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("could not build the expected hmac", e);
        }
    }

    @Test
    @DisplayName("accepts the hmac Paymob delivers as a query parameter and normalizes the event")
    void acceptsValidHmac() {
        String body = payload(30050, true);

        // No header at all — exactly what Paymob sends. The controller surfaces ?hmac= in this map.
        GatewayEvent event = gateway.parseAndVerifyWebhook(body, Map.of("hmac", hmacFor(body)));

        assertThat(event.eventId()).isEqualTo("txn_1");
        assertThat(event.gatewaySessionId()).isEqualTo("order_1");
        assertThat(event.gatewayPaymentId()).isEqualTo("txn_1");
        assertThat(event.status()).isEqualTo(PaymentAttemptStatus.SUCCEEDED);
        assertThat(event.isRefund()).isFalse();
    }

    @Test
    @DisplayName("rejects a payload tampered with after signing")
    void rejectsTamperedPayload() {
        String signedHmac = hmacFor(payload(30050, true));
        String tampered = payload(1, true); // attacker rewrites the amount

        assertThatThrownBy(() -> gateway.parseAndVerifyWebhook(tampered, Map.of("hmac", signedHmac)))
                .isInstanceOf(WebhookSignatureException.class);
    }

    @Test
    @DisplayName("rejects a delivery with no hmac on any channel")
    void rejectsMissingHmac() {
        assertThatThrownBy(() -> gateway.parseAndVerifyWebhook(payload(30050, true), Map.of()))
                .isInstanceOf(WebhookSignatureException.class)
                .hasMessageContaining("missing hmac");
    }

    @Test
    @DisplayName("a correctly signed failure normalizes to FAILED with the gateway's reason")
    void normalizesSignedFailure() {
        String body = payload(30050, false);

        GatewayEvent event = gateway.parseAndVerifyWebhook(body, Map.of("hmac", hmacFor(body)));

        assertThat(event.status()).isEqualTo(PaymentAttemptStatus.FAILED);
        assertThat(event.failureReason()).isEqualTo("declined");
    }
}
