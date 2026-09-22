package com.gr74.booking.dto;

import com.gr74.booking.model.Screen;
import com.gr74.booking.model.ScreenType;

/**
 * Response for a screen, carrying the owning theater id.
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
