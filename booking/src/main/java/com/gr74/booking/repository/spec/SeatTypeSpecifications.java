package com.gr74.booking.repository.spec;

import org.springframework.data.jpa.domain.Specification;

import com.gr74.booking.controller.dto.SeatTypeFilter;
import com.gr74.booking.model.SeatType;

/**
 * Composable {@link Specification} fragments for {@link SeatType} queries. Absent fields are no-ops.
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
