package com.gr74.catalog.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import com.gr74.catalog.model.Movie;

/** Lean list-row for {@code GET /movies} — poster, title, year, rating, genres. */
public record MovieSummaryDto(
        Long id,
        String title,
        LocalDate releaseDate,
        BigDecimal voteAverage,
        String posterPath,
        List<GenreDto> genres) {

    public static MovieSummaryDto from(Movie movie) {
        List<GenreDto> genres = movie.getGenres().stream()
                .map(GenreDto::from)
                .toList();
        return new MovieSummaryDto(
                movie.getId(),
                movie.getTitle(),
                movie.getReleaseDate(),
                movie.getVoteAverage(),
                movie.getPosterPath(),
                genres);
    }
}
