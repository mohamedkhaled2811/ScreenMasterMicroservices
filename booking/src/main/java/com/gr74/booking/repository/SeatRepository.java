package com.gr74.booking.repository;

import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import com.gr74.booking.model.Seat;

/**
 * Spring Data repository for {@link Seat}.
 *
 * <p>{@code findByScreenId...} lists a screen's seats (ordered for a stable, human-friendly layout). It
 * carries an {@link EntityGraph} on {@code seatType} so the {@code SeatResponse} mapper can read the
 * seat-type <em>name</em> after the transaction — under {@code open-in-view: false} a lazy
 * {@code seatType} would otherwise throw {@code LazyInitializationException} during serialization (and
 * the fetch also avoids the N+1 a per-seat lazy load would cause).
 * {@code existsByScreenIdAndSeatRowAndSeatNumber} pre-checks the {@code uq_seats_screen_row_number}
 * constraint before placing a seat — the pre-check the bulk grid generator uses to skip positions that
 * already exist, so re-running it is idempotent.
 *
 * <p>Extends {@link JpaSpecificationExecutor} for the paged, filtered {@code GET /screens/{id}/seats}.
 * The {@code SeatResponse} mapper reads {@code seatType.getName()}, which <em>initializes</em> the lazy
 * {@code @ManyToOne} — so {@link #findSeatPage} fetches {@code seatType} inside the transaction via an
 * {@code EntityGraph}. See {@code docs/concepts/pagination-and-filtering.md}.
 */
public interface SeatRepository extends JpaRepository<Seat, Long>, JpaSpecificationExecutor<Seat> {

    @EntityGraph(attributePaths = "seatType")
    List<Seat> findByScreenIdOrderBySeatRowAscSeatNumberAsc(Long screenId);

    boolean existsByScreenIdAndSeatRowAndSeatNumber(Long screenId, String seatRow, Integer seatNumber);

    /** Re-fetch a batch of seats <em>with</em> their {@code seatType} in one query (join, not lazy). */
    @EntityGraph(attributePaths = "seatType")
    List<Seat> findWithSeatTypeByIdIn(List<Long> ids);

    /**
     * Page seats matching {@code spec}, with each seat's {@code seatType} fetched so the
     * {@code SeatResponse} mapper can read its name after the transaction. Done in two steps — page the
     * ids (collection-free, so SQL {@code LIMIT}/{@code OFFSET} is exact), then re-fetch that page with
     * the {@code seatType} graph and restore the requested sort order (the {@code IN} query doesn't
     * preserve it). Mirrors catalog's {@code findMoviePage}.
     */
    default Page<Seat> findSeatPage(Specification<Seat> spec, Pageable pageable) {
        Page<Long> idPage = findAll(spec, pageable).map(Seat::getId);
        if (idPage.isEmpty()) {
            return Page.empty(pageable);
        }
        List<Seat> withType = findWithSeatTypeByIdIn(idPage.getContent());
        List<Seat> ordered = idPage.getContent().stream()
                .map(id -> withType.stream().filter(s -> s.getId().equals(id)).findFirst().orElseThrow())
                .toList();
        return new PageImpl<>(ordered, pageable, idPage.getTotalElements());
    }
}
