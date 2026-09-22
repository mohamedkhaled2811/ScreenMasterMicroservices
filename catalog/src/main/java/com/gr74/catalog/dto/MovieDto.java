package com.gr74.catalog.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import com.gr74.catalog.model.Movie;

/**
 * Response for {@code GET /movies/{id}} — full movie detail.
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
