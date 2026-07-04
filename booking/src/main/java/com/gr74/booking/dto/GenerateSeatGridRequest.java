package com.gr74.booking.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * Body of {@code POST /screens/{screenId}/seats/grid} — the convenience generator (plan option 5B)
 * that bulk-creates an {@code rows × seatsPerRow} grid so seeding a demo screen isn't dozens of calls.
 *
 * <p>Rows are labelled A, B, C, … (so {@code rows} is capped at 26 — one letter per row); seats within
 * a row are numbered {@code 1..seatsPerRow}. Every generated seat gets {@code seatTypeId}. Re-running
 * with an overlapping grid is idempotent: positions that already exist are skipped, not rejected.
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
