package com.gr74.booking.repository;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.gr74.booking.model.Showtime;

/**
 * Spring Data repository for {@link Showtime}.
 *
 * <p>Reads mirror the monolith's showtime endpoints: all showtimes for a movie, only the upcoming ones
 * (on or after a given date), and all for a screen. {@code movieId} is the cross-service-cut column, so
 * these queries filter on a bare id — no join to Catalog. {@code existsBy...} pre-checks the
 * {@code uq_showtimes_slot} constraint (screen + movie + date + time) before insert.
 */
public interface ShowtimeRepository extends JpaRepository<Showtime, Long> {

    List<Showtime> findByMovieIdOrderByShowDateAscShowTimeAsc(Long movieId);

    List<Showtime> findByMovieIdAndShowDateGreaterThanEqualOrderByShowDateAscShowTimeAsc(
            Long movieId, LocalDate fromDate);

    List<Showtime> findByScreenIdOrderByShowDateAscShowTimeAsc(Long screenId);

    boolean existsByScreenIdAndMovieIdAndShowDateAndShowTime(
            Long screenId, Long movieId, LocalDate showDate, LocalTime showTime);

    /**
     * A showtime with its screen <em>and</em> that screen's theater already fetched.
     *
     * <p>Needed by the booking write path, which must read {@code theater.currency} to snapshot it onto
     * the booking. Both associations are {@code LAZY} and we run {@code open-in-view: false}, so
     * touching them outside this fetch join would throw {@code LazyInitializationException} — an
     * explicit join is the honest fix, not widening the mapping to EAGER (which would pay the cost on
     * every other showtime read too).
     */
    @Query("""
            select s from Showtime s
              join fetch s.screen sc
              join fetch sc.theater
             where s.id = :id
            """)
    Optional<Showtime> findWithScreenAndTheaterById(@Param("id") Long id);
}
