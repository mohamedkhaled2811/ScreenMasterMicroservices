package com.gr74.payment.client;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * The narrow slice of a booking that Payment needs in order to decide whether to open a checkout.
 *
 * <p>Deliberately <b>not</b> the whole booking: Payment has no business knowing about seats,
 * showtimes, or movie ids. This is a purpose-built read (BUILD_PLAN 3.1) that answers exactly the
 * four guard questions — does it exist, whose is it, is it in a payable state, and has its hold
 * lapsed — plus the amount, which Payment must take from Booking and never from the client.
 *
 * @param bookingId   echoed back for correlation
 * @param userId      whose booking it is; compared against the caller
 * @param status      Booking's own lifecycle state, as a string — Payment does not import Booking's enum
 * @param expiresAt   the seat-hold deadline (NOT the gateway session's)
 * @param totalAmount the authoritative amount to charge
 * @param currency    ISO 4217, which also decides which gateways can settle it
 */
public record BookingPayability(
        Long bookingId,
        String userId,
        String status,
        Instant expiresAt,
        BigDecimal totalAmount,
        String currency) {

    /** Booking's only payable state. A string compare because the enum lives in another service. */
    public boolean isPending() {
        return "PENDING".equals(status);
    }

    /** Whether the seat hold is still good. Distinct from a lapsed gateway session. */
    public boolean isHoldLive(Instant now) {
        return expiresAt != null && expiresAt.isAfter(now);
    }
}
