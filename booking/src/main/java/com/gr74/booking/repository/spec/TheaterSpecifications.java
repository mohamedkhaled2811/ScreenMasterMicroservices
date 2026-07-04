package com.gr74.booking.repository.spec;

import org.springframework.data.jpa.domain.Specification;

import com.gr74.booking.controller.dto.TheaterFilter;
import com.gr74.booking.model.Theater;

/**
 * Composable {@link Specification} fragments for querying {@link Theater} dynamically — the same idiom
 * as catalog's {@code MovieSpecifications}.
 *
 * <p>Each method returns one predicate; {@link #from(TheaterFilter)} {@code AND}-combines them. An
 * absent field contributes an always-true {@code noOp()} fragment, so an empty filter yields an
 * unrestricted (but still paged) query and any subset of fields composes cleanly — no repository
 * method per filter combination. Built on the JPA Criteria API, so it's type-safe and injection-free.
 * See {@code docs/concepts/pagination-and-filtering.md}.
 */
public final class TheaterSpecifications {

    private TheaterSpecifications() {
    }

    /**
     * Build the combined specification for a filter. Each fragment is a no-op (always-true) when its
     * field is absent, so {@link Specification#allOf} {@code AND}-combines them uniformly. We return a
     * no-op rather than {@code null} because this Spring Data version rejects {@code null} members.
     */
    public static Specification<Theater> from(TheaterFilter f) {
        return Specification.allOf(
                nameContains(f.name()),
                locationContains(f.location()));
    }

    /** An always-true predicate: the identity element for {@code AND}, used when a field is absent. */
    private static Specification<Theater> noOp() {
        return (root, query, cb) -> cb.conjunction();
    }

    /** Case-insensitive substring match on {@code name}; no-op if null/blank. */
    public static Specification<Theater> nameContains(String name) {
        if (name == null || name.isBlank()) {
            return noOp();
        }
        String pattern = "%" + name.trim().toLowerCase() + "%";
        return (root, query, cb) -> cb.like(cb.lower(root.get("name")), pattern);
    }

    /** Case-insensitive substring match on {@code location}; no-op if null/blank. */
    public static Specification<Theater> locationContains(String location) {
        if (location == null || location.isBlank()) {
            return noOp();
        }
        String pattern = "%" + location.trim().toLowerCase() + "%";
        return (root, query, cb) -> cb.like(cb.lower(root.get("location")), pattern);
    }
}
