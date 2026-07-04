package com.gr74.booking.controller.dto;

/**
 * Query-parameter surface of {@code GET /screens/{screenId}/seats}. The {@code screenId} comes from the
 * path; this filters <em>within</em> that screen's seats. Every field is optional (null/blank = no
 * predicate). Turned into composed {@link com.gr74.booking.repository.spec.SeatSpecifications} fragments
 * ({@code AND}-combined).
 */
public record SeatFilter(

        /** Exact (case-insensitive) match on the row label ("A", "B", …). */
        String seatRow,

        /** Exact match: only seats of this seat-type id. */
        Long seatTypeId) {

}
