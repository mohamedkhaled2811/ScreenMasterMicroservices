package com.gr74.booking.exception;

/**
 * Showtime with bookings cannot be deleted; maps to {@code BOOKING_SHOWTIME_HAS_BOOKINGS} (409).
 */
public class ShowtimeHasBookingsException extends BookingException {

    public ShowtimeHasBookingsException(long showtimeId) {
        super(BookingErrorCode.BOOKING_SHOWTIME_HAS_BOOKINGS,
                "Showtime id=" + showtimeId + " cannot be deleted because it has bookings");
    }
}
