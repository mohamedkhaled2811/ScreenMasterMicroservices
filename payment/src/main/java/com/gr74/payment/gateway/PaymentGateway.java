package com.gr74.payment.gateway;

import java.util.Map;
import java.util.Set;

import com.gr74.payment.model.PaymentGatewayType;

/**
 * The seam between this service and whatever actually moves the money.
 *
 * <p>The interface is shaped around what <em>every</em> gateway can do — open a hosted session,
 * report a status, refund, verify a signature — not around any one gateway's API. Everything outside
 * {@code com.gr74.payment.gateway} holds a {@code PaymentGateway}, never a
 * {@link PaymentGatewayType}, so there is <b>no gateway-specific {@code if/else} anywhere in the
 * service</b>. Adding a gateway is a new constant plus a new {@code @Component}; nothing existing
 * changes.
 *
 * <p><b>Note what is absent: there is no {@code charge(card)} method.</b> We only ever ask a gateway
 * to host the payment form. That shape is deliberate — it is what keeps card numbers and CVV out of
 * ScreenMaster entirely, and PCI scope at its smallest. An implementation that accepted raw card
 * data would not fit this port.
 *
 * <p>Each adapter is also responsible for <b>normalization</b>: converting our
 * {@code BigDecimal} + ISO-4217 amounts into the gateway's minor-unit format, and its status
 * vocabulary back into ours. See {@code docs/concepts/payment-gateway-integration.md}.
 */
public interface PaymentGateway {

    /** The registry key. */
    PaymentGatewayType type();

    /**
     * ISO-4217 codes this gateway can actually settle. Declared per adapter rather than configured
     * centrally, because a gateway is the only thing that knows what it can take: Paymob settles
     * EGP, Stripe test mode settles USD. Drives the gateway picker and the currency guard on
     * {@code POST /payments}.
     */
    Set<String> supportedCurrencies();

    /**
     * Open a hosted checkout session and return where to send the user.
     *
     * <p>Implementations MUST forward {@link GatewaySessionRequest#idempotencyKey()} to the gateway
     * where it supports one: if this call times out we cannot know whether it was processed, and the
     * key is what makes the retry safe.
     *
     * @throws GatewayException when the gateway is unreachable or rejects the request
     */
    GatewaySession createSession(GatewaySessionRequest request);

    /**
     * Ask the gateway what it believes about a payment — pull-based truth for reconciliation and for
     * the page the user lands on after checkout. Without this, a webhook we never received is
     * unrecoverable.
     *
     * <p>Takes both ids because adapters disagree on which one keys their status API (Stripe: the
     * checkout-session id; Paymob: the transaction id) — each adapter picks the one it needs, so no
     * caller ever branches on gateway type.
     *
     * @throws GatewayException when the gateway is unreachable
     */
    GatewayPaymentStatus fetchStatus(GatewayStatusQuery query);

    /**
     * Refund all or part of a captured payment. Most gateways confirm asynchronously, so a
     * {@link com.gr74.payment.model.RefundStatus#PENDING} result is normal and expected.
     *
     * @throws GatewayException when the gateway is unreachable or rejects the refund outright
     */
    RefundResult refund(GatewayRefundRequest request);

    /**
     * Verify a webhook's signature and parse it into our vocabulary.
     *
     * <p>Takes the <b>raw body string</b>, not a parsed DTO, because signatures are computed over
     * exact bytes — a Jackson round-trip reorders keys and changes whitespace, which breaks
     * verification. Implementations must compare signatures in <b>constant time</b>
     * ({@code MessageDigest.isEqual}, never {@code String.equals}), since a timing difference leaks
     * the expected signature byte by byte.
     *
     * @throws WebhookSignatureException when the signature does not verify — the caller stores the
     *                                   delivery with {@code signatureValid = false} and answers 400
     */
    GatewayEvent parseAndVerifyWebhook(String rawPayload, Map<String, String> headers);
}
