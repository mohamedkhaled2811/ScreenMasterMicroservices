package com.gr74.booking.controller.dto;

import com.gr74.booking.model.ScreenType;

/**
 * Query parameters for {@code GET /theaters/{theaterId}/screens}. Every field is optional; absent fields add no predicate.
 */
public record ScreenFilter(

        /** Case-insensitive substring match on the screen {@code name}. */
        String name,

        /** Exact match on the screen type ({@code SCREEN_2D}, {@code SCREEN_3D}, …). */
        ScreenType screenType) {

}
