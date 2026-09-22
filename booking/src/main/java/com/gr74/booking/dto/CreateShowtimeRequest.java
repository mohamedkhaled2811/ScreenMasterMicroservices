package com.gr74.booking.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;

import com.fasterxml.jackson.annotation.JsonFormat;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;

/**
 * Body of {@code POST /showtimes}. The movie id is validated live against Catalog; {@code showTime} uses {@code HH:mm}.
 */
public record CreateShowtimeRequest(

        @NotNull
        Long movieId,

        @NotNull
        Long screenId,

        @NotNull
        LocalDate showDate,

        @NotNull
        @JsonFormat(pattern = "HH:mm")
        LocalTime showTime,

        @NotNull
        @DecimalMin(value = "0.00", inclusive = false) // must be > 0
        @Digits(integer = 8, fraction = 2)
        BigDecimal basePrice) {
}
