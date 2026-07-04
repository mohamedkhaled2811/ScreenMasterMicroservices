package com.gr74.booking.repository;

import java.util.List;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

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
 */
public interface SeatRepository extends JpaRepository<Seat, Long> {

    @EntityGraph(attributePaths = "seatType")
    List<Seat> findByScreenIdOrderBySeatRowAscSeatNumberAsc(Long screenId);

    boolean existsByScreenIdAndSeatRowAndSeatNumber(Long screenId, String seatRow, Integer seatNumber);
}
