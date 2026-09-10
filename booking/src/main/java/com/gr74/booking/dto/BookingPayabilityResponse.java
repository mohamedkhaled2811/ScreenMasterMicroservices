package com.gr74.booking.dto;

import java.math.BigDecimal;
import java.time.Instant;

import com.gr74.booking.model.Booking;

/**
 * The narrow slice of a booking the Payment service needs before opening a checkout session.
 *
 * <p>Deliberately <b>not</b> {@link BookingResponse}: Payment has no business knowing about seats,
 * showtimes, or movie ids. This exposes exactly the four facts its guards ask — does the booking
 * exist, whose is it, is it in a payable state, has its hold lapsed — plus the authoritative amount.
 *
 * <p><b>Why the amount lives here at all:</b> Payment must never take a price from the client, or a
 * browser could buy a 300 EGP ticket for 1. Booking owns pricing, so Booking is what Payment asks.
 *
 * <p>{@code status} travels as a plain string: Payment does not (and must not) import Booking's enum —
 * that would be shared code across a service boundary. The string is the contract.
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
