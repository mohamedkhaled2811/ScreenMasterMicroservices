package com.gr74.booking.dto;

import java.math.BigDecimal;
import java.time.Instant;

import com.gr74.booking.model.Booking;
import com.gr74.booking.model.BookingStatus;
import com.gr74.booking.model.PaymentStatus;

/**
 * A row of the "my bookings" list — a booking merged with its movie title. <b>This is the shape the M2
 * composition produces:</b> the booking fields come from booking-db; {@code movieTitle} is resolved
 * <em>live</em> from Catalog by the snapshotted {@code movieId} and merged in memory (the JOIN that used
 * to be a single SQL statement, now hand-written across the network).
 *
 * <p>{@code movieTitle} is <b>nullable</b> on purpose: when Catalog is unreachable the read
 * <em>degrades</em> rather than failing — the booking still renders with {@code movieTitle: null} and a
 * logged warning, instead of a 503 taking down the whole list. That degrade-vs-fail choice is the
 * partial-failure cost this endpoint exists to make you feel, and it's the exact contrast Part 3 (the
 * CQRS read model) resolves — a local title copy "wouldn't even notice Catalog was down".
 */
public record MyBookingDto(
        Long id,
        String bookingReference,
        Long showtimeId,
        Long movieId,
        String movieTitle,
        BookingStatus status,
        PaymentStatus paymentStatus,
        BigDecimal totalAmount,
        Instant expiresAt) {

    /** Merge a booking with a resolved title ({@code null} if Catalog didn't return one). */
    public static MyBookingDto of(Booking booking, String movieTitle) {
        return new MyBookingDto(
                booking.getId(),
                booking.getBookingReference(),
                booking.getShowtimeId(),
                booking.getMovieId(),
                movieTitle,
                booking.getStatus(),
                booking.getPaymentStatus(),
                booking.getTotalAmount(),
                booking.getExpiresAt());
    }
}
