package com.gr74.payment.gateway;

import java.math.BigDecimal;

/**
 * What an adapter needs to open a hosted checkout session.
 *
 * <p>Everything here is in <b>our</b> vocabulary: a {@link BigDecimal} amount with an ISO-4217
 * currency, our own ids, our own return URLs. Translating this into the gateway's dialect — integer
 * minor units, its field names, its auth scheme — is the adapter's job and happens nowhere else.
 *
 * @param paymentId      our payment id, for correlating the webhook back to the obligation
 * @param attemptId      our attempt id, used to build the return URLs
 * @param bookingId      passed to the gateway as metadata so a human reading its dashboard can tell
 *                       what the charge was for
 * @param userId         the payer, for gateway-side metadata only
 * @param amount         never a {@code double}; the adapter converts to minor units
 * @param currency       ISO 4217; the adapter must have declared it in {@code supportedCurrencies()}
 * @param idempotencyKey forwarded to the gateway so a retried create cannot double-charge
 * @param returnUrl      where the gateway sends the user after a completed checkout (UX only — the
 *                       webhook, not this redirect, is what marks a payment paid)
 * @param cancelUrl      where the gateway sends the user if they abandon
 */
public record GatewaySessionRequest(
        Long paymentId,
        Long attemptId,
        Long bookingId,
        String userId,
        BigDecimal amount,
        String currency,
        String idempotencyKey,
        String returnUrl,
        String cancelUrl) {
}
