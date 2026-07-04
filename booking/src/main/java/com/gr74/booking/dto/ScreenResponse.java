package com.gr74.booking.dto;

import com.gr74.booking.model.Screen;
import com.gr74.booking.model.ScreenType;

/**
 * Response shape for a screen. Exposes the owning {@code theaterId} (the FK) rather than a nested
 * theater object — the client already knows the theater from the URL it called, and a DTO shouldn't
 * drag the {@link Screen}'s lazy {@code theater} association across the wire.
 */
public record ScreenResponse(Long id, String name, ScreenType screenType, Long theaterId) {

    public static ScreenResponse from(Screen screen) {
        return new ScreenResponse(
                screen.getId(),
                screen.getName(),
                screen.getScreenType(),
                screen.getTheater().getId());
    }
}
