package com.gr74.booking.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Body of {@code POST /theaters}. {@code currency} is a three-letter ISO-4217 code.
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
