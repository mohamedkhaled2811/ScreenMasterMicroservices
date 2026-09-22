package com.gr74.booking.dto;

import com.gr74.booking.model.ScreenType;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Body of {@code POST /theaters/{theaterId}/screens}. The owning theater comes from the path.
 */
public record CreateScreenRequest(

        @NotBlank
        @Size(max = 100)
        String name,

        @NotNull
        ScreenType screenType) {
}
