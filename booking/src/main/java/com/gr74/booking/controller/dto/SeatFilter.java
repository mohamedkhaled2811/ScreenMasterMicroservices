package com.gr74.booking.controller.dto;

/**
 * Query parameters for {@code GET /screens/{screenId}/seats}. Every field is optional; absent fields add no predicate.
 */
public record SeatFilter(

        /** Exact (case-insensitive) match on the row label ("A", "B", …). */
        String seatRow,

        /** Exact match: only seats of this seat-type id. */
        Long seatTypeId) {

}
