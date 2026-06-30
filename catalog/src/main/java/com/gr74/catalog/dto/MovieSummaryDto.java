package com.gr74.catalog.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import com.gr74.catalog.model.Movie;

/**
 * Lean list-row for {@code GET /movies} (the paged filter endpoint) — deliberately <em>not</em> the
 * full {@link MovieDto}. A search/browse result renders a card (poster, title, year, rating, genres);
 * the heavy fields (overview, tagline, backdrop, language, popularity, vote count) belong on the
 * single-movie detail page ({@code GET /movies/{id}} → {@link MovieDto}), not in a list of dozens.
 *
 * <p>Keeping the list payload small is a deliberate API choice: a 20-row page stays cheap to
 * serialize and transfer, and the contract makes clear which view each DTO serves. DTOs cross the
 * wire, not the {@link Movie} entity (project convention).
 */
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
