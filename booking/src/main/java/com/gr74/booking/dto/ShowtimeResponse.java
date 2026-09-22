package com.gr74.booking.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.gr74.booking.model.Showtime;
import com.gr74.booking.model.ShowtimeStatus;

/**
 * Response for a showtime, carrying the movie id and owning screen id.
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
