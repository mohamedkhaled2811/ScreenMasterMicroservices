package com.gr74.booking.repository.spec;

import org.springframework.data.jpa.domain.Specification;

import com.gr74.booking.controller.dto.ScreenFilter;
import com.gr74.booking.model.Screen;
import com.gr74.booking.model.ScreenType;

/**
 * Composable {@link Specification} fragments for querying {@link Screen} within a theater. The
 * theater scoping ({@code theater.id = :theaterId}) is a mandatory fragment supplied by the service —
 * it is never optional, because "list a theater's screens" is always bounded to one theater — while the
 * {@link ScreenFilter} fields are the optional, caller-supplied predicates. Same no-op-when-absent
 * idiom as {@code MovieSpecifications}. See {@code docs/concepts/pagination-and-filtering.md}.
 */
public final class ScreenSpecifications {

    private ScreenSpecifications() {
    }

    /**
     * Combine the mandatory theater scope with the optional filter fields. {@code inTheater} is always
     * applied; the rest are no-ops when absent.
     */
    public static Specification<Screen> from(long theaterId, ScreenFilter f) {
        return Specification.allOf(
                inTheater(theaterId),
                nameContains(f.name()),
                hasScreenType(f.screenType()));
    }

    /** Traverse the {@code theater} association by id — no {@link com.gr74.booking.model.Theater} load. */
    public static Specification<Screen> inTheater(long theaterId) {
        return (root, query, cb) -> cb.equal(root.get("theater").get("id"), theaterId);
    }

    private static Specification<Screen> noOp() {
        return (root, query, cb) -> cb.conjunction();
    }

    /** Case-insensitive substring match on the screen {@code name}; no-op if null/blank. */
    public static Specification<Screen> nameContains(String name) {
        if (name == null || name.isBlank()) {
            return noOp();
        }
        String pattern = "%" + name.trim().toLowerCase() + "%";
        return (root, query, cb) -> cb.like(cb.lower(root.get("name")), pattern);
    }

    /** Exact match on the {@link ScreenType}; no-op if absent. */
    public static Specification<Screen> hasScreenType(ScreenType screenType) {
        if (screenType == null) {
            return noOp();
        }
        return (root, query, cb) -> cb.equal(root.get("screenType"), screenType);
    }
}
