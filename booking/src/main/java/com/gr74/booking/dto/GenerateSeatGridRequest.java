package com.gr74.booking.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * Body of {@code POST /screens/{screenId}/seats/grid}. Rows are labelled A..Z; re-runs skip existing seats.
 */
public record GenerateSeatGridRequest(

        @NotNull
        @Positive
        @Max(value = 26, message = "rows must be <= 26 (rows are labelled A..Z)")
        Integer rows,

        @NotNull
        @Positive
        @Max(value = 100, message = "seatsPerRow must be <= 100")
        Integer seatsPerRow,

        @NotNull
        Long seatTypeId) {
}
