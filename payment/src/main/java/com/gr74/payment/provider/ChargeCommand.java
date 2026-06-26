package com.gr74.payment.provider;

import java.math.BigDecimal;

/**
 * What a provider needs to attempt a charge: the amount to capture and the idempotency key the
 * caller supplied (so a provider that supports idempotency natively — like Stripe — can forward
 * it). Money is {@link BigDecimal}, never {@code double}, to avoid binary rounding error.
 */
public record ChargeCommand(String idempotencyKey, BigDecimal amount) {
}
