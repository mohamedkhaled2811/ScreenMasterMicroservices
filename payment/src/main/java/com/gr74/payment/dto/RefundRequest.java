package com.gr74.payment.dto;

import java.math.BigDecimal;

import jakarta.validation.constraints.DecimalMin;

/**
 * Ask for money back. {@code amount} is optional — omitted means "everything still refundable".
 *
 * <p>Partial refunds are first-class: {@code amount} may be less than the payment, and several
 * such requests may accumulate, as long as their confirmed-plus-in-flight sum never exceeds what
 * was captured.
 */
public record RefundRequest(
        @DecimalMin(value = "0.01", message = "Refund amount must be positive")
        BigDecimal amount,

        /** e.g. {@code CUSTOMER_REQUEST}. Defaults server-side when omitted. */
        String reason) {
}
