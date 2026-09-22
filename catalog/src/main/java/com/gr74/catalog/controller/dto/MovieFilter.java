package com.gr74.catalog.controller.dto;

import java.math.BigDecimal;
import java.util.Collection;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/**
 * Query parameters for {@code GET /movies}. Every field is optional; absent fields add no predicate.
 */
public record MovieFilter(

        /** Case-insensitive substring match on {@code title}. */
        String title,

        /** Exact match: only movies linked to this TMDB genre id (joins {@code movie_genres}). */
        Long genreId,

        /** Exact match on original language (e.g. {@code en}). */
        String language,

        /** Release-year lower bound (inclusive). */
        @Min(value = 1888, message = "releaseYearFrom must be >= 1888") // first film ever, 1888
        @Max(value = 2100, message = "releaseYearFrom must be <= 2100")
        Integer releaseYearFrom,

        /** Release-year upper bound (inclusive). */
        @Min(value = 1888, message = "releaseYearTo must be >= 1888")
        @Max(value = 2100, message = "releaseYearTo must be <= 2100")
        Integer releaseYearTo,

        /** Minimum vote average (inclusive), in {@code [0, 10]}. */
        @DecimalMin(value = "0.0", message = "minRating must be >= 0")
        @DecimalMax(value = "10.0", message = "minRating must be <= 10")
        BigDecimal minRating,

        /** Tri-state adult flag filter. */
        Boolean adult,

        /** Restrict to these ids. Backs {@code GET /movies/batch}. */
        Collection<Long> ids) {

}
