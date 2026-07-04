package com.gr74.booking.service;

import java.time.LocalDate;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.gr74.booking.client.CatalogClient;
import com.gr74.booking.dto.CreateShowtimeRequest;
import com.gr74.booking.exception.DuplicateResourceException;
import com.gr74.booking.exception.ResourceNotFoundException;
import com.gr74.booking.model.Screen;
import com.gr74.booking.model.Showtime;
import com.gr74.booking.repository.ShowtimeRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Business logic for showtimes — the Scheduling context, absorbed into Booking.
 *
 * <p>Creating a showtime crosses <em>two</em> validation boundaries, and the difference between them
 * is the whole lesson of this part:
 * <ul>
 *   <li><b>{@code screenId} — intra-Booking.</b> The screen lives in booking-db, so we validate it with
 *       a local lookup ({@link TheaterService#requireScreen(long)}). Strongly consistent, no network.</li>
 *   <li><b>{@code movieId} — cross-service.</b> The movie lives in Catalog's database; there is no FK to
 *       lean on. Per plan option 5C (chosen path) we validate it with a <em>synchronous</em> call to
 *       Catalog ({@link CatalogClient#verifyMovieExists(long)}). This is where the cut becomes visible:
 *       showtime creation now depends on Catalog being reachable (a {@code BOOKING_CATALOG_UNAVAILABLE}
 *       503 if it isn't) — the temporal coupling the recommended 5C option deliberately avoided, taken
 *       on here by choice.</li>
 * </ul>
 *
 * <p>Slot uniqueness ({@code screen + movie + date + time}) is guarded by a pre-check plus the DB
 * {@code uq_showtimes_slot} constraint (the real guard for the concurrent race). We validate the movie
 * <em>before</em> touching the DB so a bad id fails fast without a wasted insert attempt.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ShowtimeService {

    private final ShowtimeRepository showtimeRepository;
    private final TheaterService theaterService; // reuse the intra-Booking screen lookup
    private final CatalogClient catalogClient;

    @Transactional(readOnly = true)
    public Showtime getById(long showtimeId) {
        return showtimeRepository.findById(showtimeId)
                .orElseThrow(() -> ResourceNotFoundException.showtime(showtimeId));
    }

    @Transactional(readOnly = true)
    public List<Showtime> findByMovie(long movieId) {
        return showtimeRepository.findByMovieIdOrderByShowDateAscShowTimeAsc(movieId);
    }

    /** Upcoming showtimes for a movie: those on or after {@code fromDate} (the caller passes "today"). */
    @Transactional(readOnly = true)
    public List<Showtime> findUpcomingByMovie(long movieId, LocalDate fromDate) {
        return showtimeRepository
                .findByMovieIdAndShowDateGreaterThanEqualOrderByShowDateAscShowTimeAsc(movieId, fromDate);
    }

    @Transactional(readOnly = true)
    public List<Showtime> findByScreen(long screenId) {
        return showtimeRepository.findByScreenIdOrderByShowDateAscShowTimeAsc(screenId);
    }

    @Transactional
    public Showtime create(CreateShowtimeRequest request) {
        // 1) Intra-Booking: the screen must exist (a real FK, validated locally).
        Screen screen = theaterService.requireScreen(request.screenId());

        // 2) Cross-service (5C): the movie must exist in Catalog — a synchronous call, since the DB
        //    can't enforce a FK across the service boundary. Throws MovieNotInCatalog (404) or
        //    CatalogUnavailable (503). Done before the DB write so a bad id costs nothing downstream.
        catalogClient.verifyMovieExists(request.movieId());

        // 3) Slot uniqueness pre-check (the DB constraint is the real guard for the race).
        if (showtimeRepository.existsByScreenIdAndMovieIdAndShowDateAndShowTime(
                request.screenId(), request.movieId(), request.showDate(), request.showTime())) {
            throw new DuplicateResourceException(
                    "A showtime for movie " + request.movieId() + " already exists on screen "
                            + request.screenId() + " at " + request.showDate() + " " + request.showTime());
        }

        Showtime saved = showtimeRepository.save(new Showtime(
                request.movieId(), screen, request.showDate(), request.showTime(), request.basePrice()));
        log.info("Created showtime id={} movieId={} screenId={} at {} {}",
                saved.getId(), saved.getMovieId(), request.screenId(), saved.getShowDate(), saved.getShowTime());
        return saved;
    }

    @Transactional
    public void delete(long showtimeId) {
        Showtime showtime = getById(showtimeId);
        showtimeRepository.delete(showtime);
        log.info("Deleted showtime id={}", showtimeId);
    }
}
