package com.gr74.payment.gateway;

import java.math.BigDecimal;

/**
 * Input to open a hosted checkout session, in our vocabulary; adapters translate to gateway dialect.
 *
 * @param paymentId      our payment id, for webhook correlation
 * @param attemptId      our attempt id, used to build return URLs
 * @param bookingId      gateway metadata for dashboard readability
 * @param userId         payer, gateway metadata only
 * @param amount         adapter converts to minor units
 * @param currency       ISO 4217; must be in supportedCurrencies()
 * @param idempotencyKey forwarded so retries cannot double-charge
 * @param returnUrl      post-checkout redirect (UX only; webhook marks paid)
 * @param cancelUrl      abandon redirect
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
