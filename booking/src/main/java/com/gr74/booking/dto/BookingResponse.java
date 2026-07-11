package com.gr74.booking.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import com.gr74.booking.model.Booking;
import com.gr74.booking.model.BookingStatus;
import com.gr74.booking.model.PaymentStatus;

/**
 * Response shape for a single booking (the {@code POST /bookings} result). Carries the cross-service
 * {@code movieId} as a bare id — no title here; resolving the title against Catalog is the job of the
 * "my bookings" read ({@link MyBookingDto}), which is exactly where the missing JOIN is meant to bite.
 * {@code userId} is intentionally omitted (the caller already knows who they are). DTOs cross the wire,
 * not the {@link Booking} entity.
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
