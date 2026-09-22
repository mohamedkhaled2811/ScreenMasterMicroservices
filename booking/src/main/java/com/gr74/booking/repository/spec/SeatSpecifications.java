package com.gr74.booking.repository.spec;

import org.springframework.data.jpa.domain.Specification;

import com.gr74.booking.controller.dto.SeatFilter;
import com.gr74.booking.model.Seat;

/**
 * Composable {@link Specification} fragments for {@link Seat} queries. Absent fields are no-ops.
 */
public final class SeatSpecifications {

    private SeatSpecifications() {
    }

    /** Combine the mandatory screen scope with the optional filter fields. */
    public static Specification<Seat> from(long screenId, SeatFilter f) {
        return Specification.allOf(
                onScreen(screenId),
                inRow(f.seatRow()),
                hasSeatType(f.seatTypeId()));
    }

    /** Match by screen id without loading the screen. */
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

    /** Exact match on the seat-type id; no-op if absent. */
    public static Specification<Seat> hasSeatType(Long seatTypeId) {
        if (seatTypeId == null) {
            return noOp();
        }
        return (root, query, cb) -> cb.equal(root.get("seatType").get("id"), seatTypeId);
    }
}
