package com.gr74.payment.dto;

import java.math.BigDecimal;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;

/**
 * Body of {@code POST /payments}: the amount to charge. The idempotency key travels in the
 * {@code Idempotency-Key} header, not here, because it's a transport concern (retries of the same
 * logical request reuse it), not part of the charge's business payload.
 */
public record ChargeRequest(

        @NotNull
        @DecimalMin(value = "0.00", inclusive = false) // must be > 0
        @Digits(integer = 12, fraction = 2)            // money: 2dp, sane upper bound
        BigDecimal amount) {
}
