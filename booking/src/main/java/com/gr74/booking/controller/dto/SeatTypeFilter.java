package com.gr74.booking.controller.dto;

/**
 * Query-parameter surface of {@code GET /seat-types}. Seat types are a small, slowly-growing reference
 * list, but the repo convention is "listings paginate; they never dump" — no exemptions — so this
 * endpoint is paged like the rest, with a single optional {@code name} filter for symmetry.
 */
public record SeatTypeFilter(

        /** Case-insensitive substring match on the seat-type {@code name}. */
        String name) {

}
