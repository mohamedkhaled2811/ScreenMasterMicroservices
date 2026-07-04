package com.gr74.booking.controller.dto;

import com.gr74.booking.model.ScreenType;

/**
 * Query-parameter surface of {@code GET /theaters/{theaterId}/screens}. The {@code theaterId} itself
 * comes from the path (not here) — this filters <em>within</em> that theater's screens. Every field is
 * optional; a null/blank one contributes no predicate. Turned into composed
 * {@link com.gr74.booking.repository.spec.ScreenSpecifications} fragments ({@code AND}-combined).
 *
 * <p>{@code screenType} binds from the {@link ScreenType} enum by name; an unknown value (e.g.
 * {@code ?screenType=HOLOGRAM}) is a bind failure rendered as {@code BOOKING_VALIDATION_ERROR} by
 * {@code GlobalExceptionHandler}, never a silent no-match.
 */
public record ScreenFilter(

        /** Case-insensitive substring match on the screen {@code name}. */
        String name,

        /** Exact match on the screen type ({@code SCREEN_2D}, {@code SCREEN_3D}, …). */
        ScreenType screenType) {

}
