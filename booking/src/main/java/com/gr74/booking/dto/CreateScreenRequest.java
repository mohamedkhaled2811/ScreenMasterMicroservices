package com.gr74.booking.dto;

import com.gr74.booking.model.ScreenType;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Body of {@code POST /theaters/{theaterId}/screens}. The owning theater comes from the path, not the
 * body, so it can't disagree with the URL. {@code screenType} binds directly to the {@link ScreenType}
 * enum — an unknown value is rejected by Jackson as a {@code BOOKING_VALIDATION_ERROR} (400).
 */
public record CreateScreenRequest(

        @NotBlank
        @Size(max = 100)
        String name,

        @NotNull
        ScreenType screenType) {
}
