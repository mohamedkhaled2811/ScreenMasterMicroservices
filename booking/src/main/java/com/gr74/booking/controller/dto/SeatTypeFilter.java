package com.gr74.booking.controller.dto;

/**
 * Query parameters for {@code GET /seat-types}. Every field is optional; absent fields add no predicate.
 */
public record SeatTypeFilter(

        /** Case-insensitive substring match on the seat-type {@code name}. */
        String name) {

}
