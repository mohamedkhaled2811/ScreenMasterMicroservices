package com.gr74.booking.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * Body of {@code POST /screens/{screenId}/seats} — place one seat. The owning screen comes from the
 * path; {@code seatTypeId} references an existing {@link com.gr74.booking.model.SeatType}. The
 * {@code (screen, seatRow, seatNumber)} triple must be unique, or the create is a
 * {@code BOOKING_DUPLICATE} (409).
 */
public record CreateSeatRequest(

        @NotBlank
        @Size(max = 8)
        String seatRow,

        @NotNull
        @Positive
        Integer seatNumber,

        @NotNull
        Long seatTypeId) {
}
