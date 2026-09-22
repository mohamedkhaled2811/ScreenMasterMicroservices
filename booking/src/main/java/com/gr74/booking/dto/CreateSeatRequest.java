package com.gr74.booking.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * Body of {@code POST /screens/{screenId}/seats}. The owning screen comes from the path.
 */
public record CreateSeatRequest(

        @NotBlank
        @Size(max = 8)
        String seatRow,

        @NotNull
        @Positive
        Integer seatNumber,

        @NotNull
        Long seatTypeId) {
}
