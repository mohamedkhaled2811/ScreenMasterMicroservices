package com.gr74.booking.repository.spec;

import org.springframework.data.jpa.domain.Specification;

import com.gr74.booking.controller.dto.SeatTypeFilter;
import com.gr74.booking.model.SeatType;

/**
 * Composable {@link Specification} fragments for querying {@link SeatType} dynamically. One optional
 * {@code name} filter; same no-op-when-absent idiom as the other spec classes. See
 * {@code docs/concepts/pagination-and-filtering.md}.
 */
public final class SeatTypeSpecifications {

    private SeatTypeSpecifications() {
    }

    public static Specification<SeatType> from(SeatTypeFilter f) {
        return Specification.allOf(nameContains(f.name()));
    }

    private static Specification<SeatType> noOp() {
        return (root, query, cb) -> cb.conjunction();
    }

    /** Case-insensitive substring match on {@code name}; no-op if null/blank. */
    public static Specification<SeatType> nameContains(String name) {
        if (name == null || name.isBlank()) {
            return noOp();
        }
        String pattern = "%" + name.trim().toLowerCase() + "%";
        return (root, query, cb) -> cb.like(cb.lower(root.get("name")), pattern);
    }
}
