package com.gr74.booking.repository.spec;

import org.springframework.data.jpa.domain.Specification;

import com.gr74.booking.controller.dto.TheaterFilter;
import com.gr74.booking.model.Theater;

/**
 * Composable {@link Specification} fragments for {@link Theater} queries. Absent fields are no-ops.
 */
public final class TheaterSpecifications {

    private TheaterSpecifications() {
    }

    /** Build the combined specification for a filter (AND-combined). */
    public static Specification<Theater> from(TheaterFilter f) {
        return Specification.allOf(
                nameContains(f.name()),
                locationContains(f.location()));
    }

    /** Always-true predicate, used when a field is absent. */
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
