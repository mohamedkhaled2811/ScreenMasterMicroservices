package com.gr74.booking.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.gr74.booking.model.Showtime;
import com.gr74.booking.model.ShowtimeStatus;

/**
 * Response shape for a showtime. Exposes {@code movieId} (the cross-service reference — no title here;
 * resolving the title against Catalog is the job of parts 2/3, which is exactly where the "missing
 * JOIN" is meant to bite) and the owning {@code screenId}, rather than nested entities.
 */
public record ShowtimeResponse(
        Long id,
        Long movieId,
        Long screenId,
        LocalDate showDate,
        @JsonFormat(pattern = "HH:mm") LocalTime showTime,
        BigDecimal basePrice,
        ShowtimeStatus status) {

    public static ShowtimeResponse from(Showtime showtime) {
        return new ShowtimeResponse(
                showtime.getId(),
                showtime.getMovieId(),
                showtime.getScreen().getId(),
                showtime.getShowDate(),
                showtime.getShowTime(),
                showtime.getBasePrice(),
                showtime.getStatus());
    }
}
