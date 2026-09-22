package com.gr74.booking.controller.dto;

/**
 * Query parameters for {@code GET /theaters}. Every field is optional; absent fields add no predicate.
 */
public record TheaterFilter(

        /** Case-insensitive substring match on {@code name}. */
        String name,

        /** Case-insensitive substring match on {@code location}. */
        String location) {

}
