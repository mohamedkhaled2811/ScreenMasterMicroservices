package com.gr74.booking.dto;

import java.math.BigDecimal;
import java.time.Instant;

import com.gr74.booking.model.Booking;
import com.gr74.booking.model.BookingStatus;
import com.gr74.booking.model.PaymentStatus;

/**
 * One row of the "my bookings" list: a booking merged with its movie title ({@code null} when unresolvable).
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

    /** Merge a booking with its resolved title. */
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
