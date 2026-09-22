package com.gr74.catalog.repository.spec;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collection;

import org.springframework.data.jpa.domain.Specification;

import com.gr74.catalog.controller.dto.MovieFilter;
import com.gr74.catalog.model.Movie;

import jakarta.persistence.criteria.JoinType;

/**
 * Composable {@link Specification} fragments for dynamic movie queries. Absent fields are no-ops.
 */
public final class MovieSpecifications {

    private MovieSpecifications() {
    }

    /** Build the combined specification for a filter (AND-combined). */
    public static Specification<Movie> from(MovieFilter f) {
        return Specification.allOf(
                titleContains(f.title()),
                hasGenre(f.genreId()),
                hasLanguage(f.language()),
                releasedOnOrAfter(f.releaseYearFrom()),
                releasedOnOrBefore(f.releaseYearTo()),
                ratedAtLeast(f.minRating()),
                isAdult(f.adult()),
                idIn(f.ids()));
    }

    /** Always-true predicate, used when a field is absent. */
    private static Specification<Movie> noOp() {
        return (root, query, cb) -> cb.conjunction();
    }

    /** Case-insensitive substring match on title; no-op if {@code title} is null/blank. */
    public static Specification<Movie> titleContains(String title) {
        if (title == null || title.isBlank()) {
            return noOp();
        }
        String pattern = "%" + title.trim().toLowerCase() + "%";
        return (root, query, cb) -> cb.like(cb.lower(root.get("title")), pattern);
    }

    /**
     * Restrict to movies linked to {@code genreId}. No-op if absent.
     */
    public static Specification<Movie> hasGenre(Long genreId) {
        if (genreId == null) {
            return noOp();
        }
        return (root, query, cb) -> cb.equal(root.join("genres", JoinType.LEFT).get("id"), genreId);
    }

    /** Exact (case-insensitive) match on the original language code; no-op if absent. */
    public static Specification<Movie> hasLanguage(String language) {
        if (language == null || language.isBlank()) {
            return noOp();
        }
        return (root, query, cb) -> cb.equal(cb.lower(root.get("originalLanguage")), language.trim().toLowerCase());
    }

    /** {@code release_date >= <year>-01-01}; no-op if absent. */
    public static Specification<Movie> releasedOnOrAfter(Integer year) {
        if (year == null) {
            return noOp();
        }
        LocalDate from = LocalDate.of(year, 1, 1);
        return (root, query, cb) -> cb.greaterThanOrEqualTo(root.get("releaseDate"), from);
    }

    /** {@code release_date <= <year>-12-31}; no-op if absent. */
    public static Specification<Movie> releasedOnOrBefore(Integer year) {
        if (year == null) {
            return noOp();
        }
        LocalDate to = LocalDate.of(year, 12, 31);
        return (root, query, cb) -> cb.lessThanOrEqualTo(root.get("releaseDate"), to);
    }

    /** {@code vote_average >= minRating}; no-op if absent. */
    public static Specification<Movie> ratedAtLeast(BigDecimal minRating) {
        if (minRating == null) {
            return noOp();
        }
        return (root, query, cb) -> cb.greaterThanOrEqualTo(root.get("voteAverage"), minRating);
    }

    /** Exact match on the adult flag; no-op if absent (tri-state). */
    public static Specification<Movie> isAdult(Boolean adult) {
        if (adult == null) {
            return noOp();
        }
        return (root, query, cb) -> cb.equal(root.get("adult"), adult);
    }

    /**
     * Restrict to the given ids ({@code id IN (…)}); no-op if empty. Backs batch lookup.
     */
    public static Specification<Movie> idIn(Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return noOp();
        }
        return (root, query, cb) -> root.get("id").in(ids);
    }
}
