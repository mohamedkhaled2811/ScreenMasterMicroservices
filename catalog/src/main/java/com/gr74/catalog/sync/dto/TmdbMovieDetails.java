package com.gr74.catalog.sync.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/** Inbound wire type for TMDB's {@code GET /movie/{id}}. Unknown fields are ignored. */
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
