package com.gr74.catalog.controller.dto;

import java.math.BigDecimal;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/**
 * The query-parameter surface of {@code GET /movies} — Spring binds matching request params onto
 * these record components by name (e.g. {@code ?title=matrix&minRating=7}). Every field is
 * <em>optional</em>: a null/blank value contributes no predicate, which is exactly what "filter
 * dynamically by whatever the caller sends" means. The non-null fields are turned into composable
 * {@link com.gr74.catalog.repository.spec.MovieSpecifications} fragments combined with {@code AND}.
 *
 * <p>This is a binding/validation DTO, not a wire-out type: the constraints here are the service's
 * first line of input validation (a year of 0 or a rating of 99 is rejected as
 * {@code CATALOG_VALIDATION_ERROR} before any query runs). Paging and sorting are <em>not</em> here —
 * they ride on Spring Data's {@code Pageable}, bound separately from {@code page}/{@code size}/{@code sort}.
 *
 * <p>Because a {@code record} has no no-arg constructor, Spring binds it via its canonical
 * constructor; absent params arrive as {@code null} (objects) so "absent" and "present" are
 * distinguishable.
 */
public record MovieFilter(

        /** Case-insensitive substring match on {@code title}. */
        String title,

        /** Exact match: only movies linked to this TMDB genre id (joins {@code movie_genres}). */
        Long genreId,

        /** Exact match on the two-letter {@code original_language} (e.g. {@code en}). */
        String language,

        /** Release-year lower bound (inclusive): {@code release_date >= year-01-01}. */
        @Min(value = 1888, message = "releaseYearFrom must be >= 1888") // first film ever, 1888
        @Max(value = 2100, message = "releaseYearFrom must be <= 2100")
        Integer releaseYearFrom,

        /** Release-year upper bound (inclusive): {@code release_date <= year-12-31}. */
        @Min(value = 1888, message = "releaseYearTo must be >= 1888")
        @Max(value = 2100, message = "releaseYearTo must be <= 2100")
        Integer releaseYearTo,

        /** Minimum TMDB vote average (inclusive), in {@code [0, 10]}. */
        @DecimalMin(value = "0.0", message = "minRating must be >= 0")
        @DecimalMax(value = "10.0", message = "minRating must be <= 10")
        BigDecimal minRating,

        /** Tri-state: {@code null} = no filter, {@code true}/{@code false} = match adult flag. */
        Boolean adult) {

}
