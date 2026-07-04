package com.gr74.booking.repository;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

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
}
