package com.gr74.booking.dto;

import java.math.BigDecimal;
import java.time.Instant;

import com.gr74.booking.model.Booking;

/**
 * Payability facts for one booking, read by the Payment service. Amount is authoritative here, never client-supplied.
 */
public record BookingPayabilityResponse(
        Long bookingId,
        String userId,
        String status,
        Instant expiresAt,
        BigDecimal totalAmount,
        String currency) {

    public static BookingPayabilityResponse from(Booking booking) {
        return new BookingPayabilityResponse(
                booking.getId(),
                booking.getUserId(),
                booking.getStatus().name(),
                booking.getExpiresAt(),
                booking.getTotalAmount(),
                booking.getCurrency());
    }
}
