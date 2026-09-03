package com.gr74.payment.gateway;

import java.math.BigDecimal;

/**
 * What an adapter needs to refund — possibly only part of — a captured payment.
 *
 * @param gatewayPaymentId the gateway's id for the original payment
 * @param amount           may be less than the original; partial refunds are a first-class case
 * @param currency         ISO 4217, matching the original payment
 * @param idempotencyKey   forwarded so a retried refund cannot refund twice
 * @param reason           our reason code, e.g. {@code BOOKING_EXPIRED}
 */
public record GatewayRefundRequest(
        String gatewayPaymentId,
        BigDecimal amount,
        String currency,
        String idempotencyKey,
        String reason) {
}
