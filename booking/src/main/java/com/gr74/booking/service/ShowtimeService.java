package com.gr74.booking.service;

import java.time.LocalDate;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.gr74.booking.client.CatalogClient;
import com.gr74.booking.dto.CreateShowtimeRequest;
import com.gr74.booking.exception.DuplicateResourceException;
import com.gr74.booking.exception.ResourceNotFoundException;
import com.gr74.booking.exception.ShowtimeHasBookingsException;
import com.gr74.booking.model.Screen;
import com.gr74.booking.model.Showtime;
import com.gr74.booking.repository.BookingRepository;
import com.gr74.booking.repository.ShowtimeRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Business logic for showtimes. The screen is validated locally; the movie is validated
 * with a synchronous call to Catalog. Slot uniqueness has a pre-check plus the DB constraint.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ShowtimeService {

    private final ShowtimeRepository showtimeRepository;
    private final BookingRepository bookingRepository;
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

    /** Upcoming showtimes for a movie on or after {@code fromDate}. */
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
        // 1) The screen must exist (local lookup).
        Screen screen = theaterService.requireScreen(request.screenId());

        // 2) The movie must exist in Catalog (throws 404 or 503); before the DB write.
        catalogClient.verifyMovieExists(request.movieId());

        // 3) Slot uniqueness pre-check (the DB constraint guards the race).
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
        if (bookingRepository.existsByShowtimeId(showtimeId)) {
            throw new ShowtimeHasBookingsException(showtimeId);
        }
        showtimeRepository.delete(showtime);
        log.info("Deleted showtime id={}", showtimeId);
    }
}
