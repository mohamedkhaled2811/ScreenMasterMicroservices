package com.gr74.catalog.sync.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Inbound wire type for TMDB's {@code GET /movie/{id}} (movie-details) response — the full record the
 * sync hydrates each movie from. Only the fields the {@code movies} table needs are mapped; TMDB
 * sends far more, so {@code @JsonIgnoreProperties(ignoreUnknown = true)} keeps an upstream addition
 * from breaking deserialization (a resilience choice — we don't control TMDB's payload).
 *
 * <p>This is an <em>inbound</em> DTO (TMDB → us), distinct from the outbound {@code MovieDto} we
 * serve. {@code release_date} can be an empty string upstream for unreleased films; a custom-free
 * mapping treats it as {@code null} in {@link com.gr74.catalog.sync.TmdbApiClient}.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TmdbMovieDetails(
        Long id,
        String title,
        @JsonProperty("original_title") String originalTitle,
        String overview,
        String tagline,
        @JsonProperty("release_date") String releaseDate,
        Integer runtime,
        String status,
        @JsonProperty("original_language") String originalLanguage,
        BigDecimal popularity,
        @JsonProperty("vote_average") BigDecimal voteAverage,
        @JsonProperty("vote_count") Integer voteCount,
        @JsonProperty("poster_path") String posterPath,
        @JsonProperty("backdrop_path") String backdropPath,
        boolean adult,
        List<TmdbGenre> genres) {
}
