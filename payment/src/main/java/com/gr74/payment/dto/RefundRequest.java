package com.gr74.payment.dto;

import java.math.BigDecimal;

import jakarta.validation.constraints.DecimalMin;

/**
 * Refund request; omitted amount means everything still refundable. Partial amounts may accumulate.
 */
public record RefundRequest(
        @DecimalMin(value = "0.01", message = "Refund amount must be positive")
        BigDecimal amount,

        /** e.g. {@code CUSTOMER_REQUEST}. Defaults server-side when omitted. */
        String reason) {
}
