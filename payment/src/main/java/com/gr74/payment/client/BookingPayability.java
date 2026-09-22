package com.gr74.payment.client;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Narrow slice of a booking Payment needs to decide whether to open a checkout.
 */
public record BookingPayability(
        Long bookingId,
        String userId,
        String status,
        Instant expiresAt,
        BigDecimal totalAmount,
        String currency) {

    /** Booking's only payable state. */
    public boolean isPending() {
        return "PENDING".equals(status);
    }

    /** Whether the seat hold is still good. */
    public boolean isHoldLive(Instant now) {
        return expiresAt != null && expiresAt.isAfter(now);
    }
}
