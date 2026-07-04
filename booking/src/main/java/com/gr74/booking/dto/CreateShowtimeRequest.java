package com.gr74.booking.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;

import com.fasterxml.jackson.annotation.JsonFormat;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;

/**
 * Body of {@code POST /showtimes}. The {@code screenId} is validated intra-Booking (the screen must
 * exist); the {@code movieId} is validated <em>across the service boundary</em> by a synchronous call
 * to Catalog (plan option 5C, chosen path) — the database can't enforce it because Catalog owns movies
 * in another database.
 *
 * <p>{@code showTime} is bound as {@code HH:mm} ({@link JsonFormat}) so a caller sends "19:30", not a
 * full ISO time. {@code basePrice} must be positive.
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
