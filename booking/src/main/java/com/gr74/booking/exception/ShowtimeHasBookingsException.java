package com.gr74.booking.exception;

/**
 * Thrown when an attempt is made to delete a {@code Showtime} that still has {@code Booking} rows
 * referencing it. The database FK {@code fk_bookings_showtime} rejects the delete with
 * {@code ON DELETE NO ACTION}; this exception lets the service fail fast and return a stable
 * {@link BookingErrorCode#BOOKING_SHOWTIME_HAS_BOOKINGS} (HTTP 409 Conflict) instead of surfacing a
 * raw {@code DataIntegrityViolationException}.
 */
public class ShowtimeHasBookingsException extends BookingException {

    public ShowtimeHasBookingsException(long showtimeId) {
        super(BookingErrorCode.BOOKING_SHOWTIME_HAS_BOOKINGS,
                "Showtime id=" + showtimeId + " cannot be deleted because it has bookings");
    }
}
