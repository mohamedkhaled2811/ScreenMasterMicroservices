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
 * Spring Data repository for {@link Showtime}. {@code movieId} filters on a bare id (no join to Catalog).
 */
public interface ShowtimeRepository extends JpaRepository<Showtime, Long> {

    List<Showtime> findByMovieIdOrderByShowDateAscShowTimeAsc(Long movieId);

    List<Showtime> findByMovieIdAndShowDateGreaterThanEqualOrderByShowDateAscShowTimeAsc(
            Long movieId, LocalDate fromDate);

    List<Showtime> findByScreenIdOrderByShowDateAscShowTimeAsc(Long screenId);

    boolean existsByScreenIdAndMovieIdAndShowDateAndShowTime(
            Long screenId, Long movieId, LocalDate showDate, LocalTime showTime);

    /**
     * A showtime with its screen and that screen's theater already fetched (needed to snapshot
     * {@code theater.currency} onto the booking).
     */
    @Query("""
            select s from Showtime s
              join fetch s.screen sc
              join fetch sc.theater
             where s.id = :id
            """)
    Optional<Showtime> findWithScreenAndTheaterById(@Param("id") Long id);
}
