package com.gr74.payment.gateway;

import java.math.BigDecimal;

/**
 * Input to refund a captured payment; partial refunds are first-class.
 *
 * @param gatewayPaymentId gateway id for the original payment
 * @param amount           may be less than the original
 * @param currency         ISO 4217, matching the original
 * @param idempotencyKey   forwarded so retries cannot refund twice
 * @param reason           our reason code, e.g. {@code BOOKING_EXPIRED}
 */
public record GatewayRefundRequest(
        String gatewayPaymentId,
        BigDecimal amount,
        String currency,
        String idempotencyKey,
        String reason) {
}
