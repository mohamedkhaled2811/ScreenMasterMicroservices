package com.gr74.booking.repository.spec;

import org.springframework.data.jpa.domain.Specification;

import com.gr74.booking.controller.dto.SeatFilter;
import com.gr74.booking.model.Seat;

/**
 * Composable {@link Specification} fragments for querying {@link Seat} within a screen. As with
 * {@link ScreenSpecifications}, the screen scoping is a mandatory fragment (a seat listing is always
 * bounded to one screen) and the {@link SeatFilter} fields are the optional predicates. Same
 * no-op-when-absent idiom. See {@code docs/concepts/pagination-and-filtering.md}.
 */
public final class SeatSpecifications {

    private SeatSpecifications() {
    }

    /**
     * Combine the mandatory screen scope with the optional filter fields. {@code onScreen} is always
     * applied; the rest are no-ops when absent.
     */
    public static Specification<Seat> from(long screenId, SeatFilter f) {
        return Specification.allOf(
                onScreen(screenId),
                inRow(f.seatRow()),
                hasSeatType(f.seatTypeId()));
    }

    /** Traverse the {@code screen} association by id — no {@link com.gr74.booking.model.Screen} load. */
    public static Specification<Seat> onScreen(long screenId) {
        return (root, query, cb) -> cb.equal(root.get("screen").get("id"), screenId);
    }

    private static Specification<Seat> noOp() {
        return (root, query, cb) -> cb.conjunction();
    }

    /** Exact (case-insensitive) match on the row label; no-op if null/blank. */
    public static Specification<Seat> inRow(String seatRow) {
        if (seatRow == null || seatRow.isBlank()) {
            return noOp();
        }
        return (root, query, cb) -> cb.equal(cb.lower(root.get("seatRow")), seatRow.trim().toLowerCase());
    }

    /** Exact match on the {@code seatType} id; no-op if absent. */
    public static Specification<Seat> hasSeatType(Long seatTypeId) {
        if (seatTypeId == null) {
            return noOp();
        }
        return (root, query, cb) -> cb.equal(root.get("seatType").get("id"), seatTypeId);
    }
}
