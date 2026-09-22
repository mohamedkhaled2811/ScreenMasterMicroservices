package com.gr74.booking.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import com.gr74.booking.model.Booking;
import com.gr74.booking.model.BookingStatus;
import com.gr74.booking.model.PaymentStatus;

/**
 * Response for {@code POST /bookings} — one booking with its reserved seats.
 */
public record BookingResponse(
        Long id,
        String bookingReference,
        Long showtimeId,
        Long movieId,
        BookingStatus status,
        PaymentStatus paymentStatus,
        BigDecimal totalAmount,
        Instant expiresAt,
        List<BookingSeatResponse> seats) {

    public static BookingResponse from(Booking booking) {
        List<BookingSeatResponse> seats = booking.getSeats().stream()
                .map(BookingSeatResponse::from)
                .toList();
        return new BookingResponse(
                booking.getId(),
                booking.getBookingReference(),
                booking.getShowtimeId(),
                booking.getMovieId(),
                booking.getStatus(),
                booking.getPaymentStatus(),
                booking.getTotalAmount(),
                booking.getExpiresAt(),
                seats);
    }
}
