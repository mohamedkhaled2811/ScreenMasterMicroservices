package com.gr74.catalog.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import com.gr74.catalog.model.Movie;

/**
 * Response for {@code GET /movies/{id}} — a DTO, not the {@link Movie} entity, so the JSON contract
 * stays decoupled from the persistence model (project convention: DTOs cross the wire). Auditing
 * fields ({@code createdDate}/{@code lastModifiedDate}) are internal and deliberately not exposed.
 *
 * <p>This is the shape Booking will consume in Phase 2 when it composes movie titles into "my
 * bookings" — it keys off {@code id} and {@code title}; the rest renders a movie page.
 */
public record MovieDto(
        Long id,
        String title,
        String originalTitle,
        String overview,
        String tagline,
        LocalDate releaseDate,
        Integer runtime,
        String status,
        String originalLanguage,
        BigDecimal popularity,
        BigDecimal voteAverage,
        Integer voteCount,
        String posterPath,
        String backdropPath,
        boolean adult,
        List<GenreDto> genres) {

    public static MovieDto from(Movie movie) {
        List<GenreDto> genres = movie.getGenres().stream()
                .map(GenreDto::from)
                .toList();
        return new MovieDto(
                movie.getId(),
                movie.getTitle(),
                movie.getOriginalTitle(),
                movie.getOverview(),
                movie.getTagline(),
                movie.getReleaseDate(),
                movie.getRuntime(),
                movie.getStatus(),
                movie.getOriginalLanguage(),
                movie.getPopularity(),
                movie.getVoteAverage(),
                movie.getVoteCount(),
                movie.getPosterPath(),
                movie.getBackdropPath(),
                movie.isAdult(),
                genres);
    }
}
