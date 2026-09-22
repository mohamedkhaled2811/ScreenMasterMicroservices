package com.gr74.booking.repository.spec;

import org.springframework.data.jpa.domain.Specification;

import com.gr74.booking.controller.dto.ScreenFilter;
import com.gr74.booking.model.Screen;
import com.gr74.booking.model.ScreenType;

/**
 * Composable {@link Specification} fragments for {@link Screen} queries. Absent fields are no-ops.
 */
public final class ScreenSpecifications {

    private ScreenSpecifications() {
    }

    /** Combine the mandatory theater scope with the optional filter fields. */
    public static Specification<Screen> from(long theaterId, ScreenFilter f) {
        return Specification.allOf(
                inTheater(theaterId),
                nameContains(f.name()),
                hasScreenType(f.screenType()));
    }

    /** Match by theater id without loading the theater. */
    public static Specification<Screen> inTheater(long theaterId) {
        return (root, query, cb) -> cb.equal(root.get("theater").get("id"), theaterId);
    }

    private static Specification<Screen> noOp() {
        return (root, query, cb) -> cb.conjunction();
    }

    /** Case-insensitive substring match on name; no-op if null/blank. */
    public static Specification<Screen> nameContains(String name) {
        if (name == null || name.isBlank()) {
            return noOp();
        }
        String pattern = "%" + name.trim().toLowerCase() + "%";
        return (root, query, cb) -> cb.like(cb.lower(root.get("name")), pattern);
    }

    /** Exact match on {@link ScreenType}; no-op if absent. */
    public static Specification<Screen> hasScreenType(ScreenType screenType) {
        if (screenType == null) {
            return noOp();
        }
        return (root, query, cb) -> cb.equal(root.get("screenType"), screenType);
    }
}
