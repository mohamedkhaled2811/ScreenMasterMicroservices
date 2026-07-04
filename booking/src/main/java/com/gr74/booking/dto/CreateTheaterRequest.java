package com.gr74.booking.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Body of {@code POST /theaters}. {@code name} is required and is the theater's unique identity;
 * {@code location} is free-text and optional. Validation here is the first line of defence — a blank
 * name is a {@code BOOKING_VALIDATION_ERROR} (400) before any query runs.
 */
public record CreateTheaterRequest(

        @NotBlank
        @Size(max = 150)
        String name,

        @Size(max = 255)
        String location) {
}
