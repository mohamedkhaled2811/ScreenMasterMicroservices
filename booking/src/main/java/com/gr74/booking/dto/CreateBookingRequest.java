package com.gr74.booking.dto;

import java.util.List;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

/**
 * Body of {@code POST /bookings}. Price, movie, and user are derived server-side, never caller-supplied.
 */
public record CreateBookingRequest(

        @NotNull
        Long showtimeId,

        @NotEmpty(message = "A booking must reserve at least one seat")
        List<Long> seatIds) {
}
