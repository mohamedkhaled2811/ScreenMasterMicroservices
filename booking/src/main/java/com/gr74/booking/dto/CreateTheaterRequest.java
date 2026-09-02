package com.gr74.booking.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Body of {@code POST /theaters}. {@code name} is required and is the theater's unique identity;
 * {@code location} is free-text and optional. Validation here is the first line of defence — a blank
 * name is a {@code BOOKING_VALIDATION_ERROR} (400) before any query runs.
 *
 * <p>{@code currency} sets what every price under this theater is denominated in, and is validated as
 * a three-letter ISO-4217 code because Payment routes on it: a typo here would mean no gateway could
 * settle the theater's bookings.
 */
public record CreateTheaterRequest(

        @NotBlank
        @Size(max = 150)
        String name,

        @Size(max = 255)
        String location,

        @NotBlank
        @Pattern(regexp = "^[A-Z]{3}$", message = "must be a 3-letter uppercase ISO-4217 code, e.g. EGP")
        String currency) {
}
