package com.gr74.payment.gateway;

import java.util.Map;
import java.util.Set;

import com.gr74.payment.model.PaymentGatewayType;

/**
 * Seam to whatever moves the money: hosted sessions only, never raw card data.
 * Adapters normalize amounts and status vocabulary; callers hold this, never a gateway type.
 */
public interface PaymentGateway {

    /** The registry key. */
    PaymentGatewayType type();

    /** ISO-4217 codes this gateway can settle. */
    Set<String> supportedCurrencies();

    /**
     * Open a hosted checkout session. Implementations forward the idempotency key where supported.
     *
     * @throws GatewayException when the gateway is unreachable or rejects the request
     */
    GatewaySession createSession(GatewaySessionRequest request);

    /**
     * Pull-based status for reconciliation. Takes both ids; each adapter picks the one it needs.
     *
     * @throws GatewayException when the gateway is unreachable
     */
    GatewayPaymentStatus fetchStatus(GatewayStatusQuery query);

    /**
     * Refund all or part of a captured payment; PENDING is normal, confirmation arrives by webhook.
     *
     * @throws GatewayException when the gateway is unreachable or rejects the refund outright
     */
    RefundResult refund(GatewayRefundRequest request);

    /**
     * Verify a webhook signature over the raw body and parse it. Comparison is constant-time.
     *
     * @throws WebhookSignatureException when the signature does not verify
     */
    GatewayEvent parseAndVerifyWebhook(String rawPayload, Map<String, String> headers);
}
