package com.gr74.booking.dto;

import java.util.List;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

/**
 * Body of {@code POST /bookings}. Deliberately small: a booking is "these seats, for this showtime" —
 * the price, the movie, and the user are <em>not</em> caller-supplied. Price/movie are derived and
 * snapshotted server-side from the showtime + seat types (a client must not be able to name its own
 * price), and the user id comes from the request identity ({@code @CurrentUser}), never the body.
 *
 * <p>{@code seatIds} must be non-empty; the service checks they belong to the showtime's screen and
 * aren't already held (the double-booking guard).
 */
public record CreateBookingRequest(

        @NotNull
        Long showtimeId,

        @NotEmpty(message = "A booking must reserve at least one seat")
        List<Long> seatIds) {
}
