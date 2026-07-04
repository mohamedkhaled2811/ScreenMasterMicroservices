package com.gr74.booking.dto;

import java.math.BigDecimal;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Body of {@code POST /seat-types}. The {@code priceMultiplier} scales a showtime's base price
 * (total = {@code basePrice × priceMultiplier}); it must be positive, or pricing would zero out or go
 * negative. {@code name} is the type's unique identity (STANDARD, PREMIUM, VIP).
 */
public record CreateSeatTypeRequest(

        @NotBlank
        @Size(max = 60)
        String name,

        @NotNull
        @DecimalMin(value = "0.00", inclusive = false) // must be > 0
        @Digits(integer = 2, fraction = 2)
        BigDecimal priceMultiplier) {
}
