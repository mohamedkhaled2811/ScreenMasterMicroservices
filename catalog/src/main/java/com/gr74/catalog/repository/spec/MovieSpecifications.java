package com.gr74.catalog.repository.spec;

import java.math.BigDecimal;
import java.time.LocalDate;

import org.springframework.data.jpa.domain.Specification;

import com.gr74.catalog.controller.dto.MovieFilter;
import com.gr74.catalog.model.Movie;

import jakarta.persistence.criteria.JoinType;

/**
 * Composable {@link Specification} fragments for querying {@link Movie} dynamically — the clean
 * rebuild of the monolith's {@code MovieSpecification} (schema doc §1).
 *
 * <p>Each method returns one predicate; {@link #from(MovieFilter)} {@code AND}-combines them. This is
 * why "filter by whatever the user sends" doesn't explode into a repository method per combination:
 * an absent field contributes an always-true {@code noOp()} fragment, so an empty filter yields an
 * unrestricted (but still paged) query and any subset of fields composes cleanly.
 *
 * <p>The whole thing is built from the JPA Criteria API, so it's type-safe and database-agnostic —
 * no string concatenation, nothing the caller can inject into. See
 * {@code docs/concepts/jpa-and-hibernate.md} (JPA Specifications).
 */
public final class MovieSpecifications {

    private MovieSpecifications() {
    }

    /**
     * Build the combined specification for a filter. Each fragment is always-true (a "conjunction"
     * no-op) when its field is absent, so {@link Specification#allOf} {@code AND}-combines them
     * uniformly — an empty filter yields an unrestricted (but still paged) query. We return a no-op
     * rather than {@code null} because this Spring Data version rejects {@code null} members.
     */
    public static Specification<Movie> from(MovieFilter f) {
        return Specification.allOf(
                titleContains(f.title()),
                hasGenre(f.genreId()),
                hasLanguage(f.language()),
                releasedOnOrAfter(f.releaseYearFrom()),
                releasedOnOrBefore(f.releaseYearTo()),
                ratedAtLeast(f.minRating()),
                isAdult(f.adult()));
    }

    /** An always-true predicate: the identity element for {@code AND}, used when a field is absent. */
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
     * Restrict to movies linked to {@code genreId} via the {@code movie_genres} join. The join is
     * {@code LEFT} so it never silently drops a movie that has no genres on an unrelated predicate;
     * combined with the equality below it still filters correctly. Distinct-ness isn't needed here
     * because we filter on a single genre id (a movie joins that genre at most once).
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
}
