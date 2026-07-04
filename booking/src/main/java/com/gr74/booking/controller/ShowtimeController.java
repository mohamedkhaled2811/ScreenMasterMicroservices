package com.gr74.booking.controller;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.gr74.booking.dto.CreateShowtimeRequest;
import com.gr74.booking.dto.ShowtimeResponse;
import com.gr74.booking.service.ShowtimeService;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * REST surface for showtimes — the Scheduling context absorbed into Booking.
 *
 * <p>Bare paths ({@code /showtimes/...}); the gateway strips the {@code /api} prefix. Reads mirror the
 * monolith's showtime endpoints (by id, by movie, upcoming-by-movie, by screen). {@code POST /showtimes}
 * validates the movie against Catalog synchronously (plan option 5C) — a bad {@code movieId} comes back
 * as {@code BOOKING_MOVIE_NOT_FOUND} (404) and a Catalog outage as {@code BOOKING_CATALOG_UNAVAILABLE}
 * (503), both rendered by {@code GlobalExceptionHandler}. DTOs cross the wire, not entities.
 *
 * <p>A {@link Clock} is injected (rather than calling {@code LocalDate.now()} directly) so "upcoming"
 * has a testable notion of "today" — a test can pin the clock.
 */
@Slf4j
@RestController
@RequestMapping("/showtimes")
@RequiredArgsConstructor
public class ShowtimeController {

    private final ShowtimeService showtimeService;
    private final Clock clock;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ShowtimeResponse create(@Valid @RequestBody CreateShowtimeRequest request) {
        return ShowtimeResponse.from(showtimeService.create(request));
    }

    @GetMapping("/{id}")
    public ShowtimeResponse getById(@PathVariable long id) {
        ShowtimeResponse response = ShowtimeResponse.from(showtimeService.getById(id));
        log.info("GET /showtimes/{} -> movieId={}", id, response.movieId());
        return response;
    }

    @GetMapping("/movie/{movieId}")
    public List<ShowtimeResponse> byMovie(@PathVariable long movieId) {
        List<ShowtimeResponse> showtimes = showtimeService.findByMovie(movieId).stream()
                .map(ShowtimeResponse::from)
                .toList();
        log.info("GET /showtimes/movie/{} -> {} showtimes", movieId, showtimes.size());
        return showtimes;
    }

    @GetMapping("/movie/upcoming/{movieId}")
    public List<ShowtimeResponse> upcomingByMovie(@PathVariable long movieId) {
        LocalDate today = LocalDate.now(clock);
        List<ShowtimeResponse> showtimes = showtimeService.findUpcomingByMovie(movieId, today).stream()
                .map(ShowtimeResponse::from)
                .toList();
        log.info("GET /showtimes/movie/upcoming/{} (from {}) -> {} showtimes", movieId, today, showtimes.size());
        return showtimes;
    }

    @GetMapping("/screen/{screenId}")
    public List<ShowtimeResponse> byScreen(@PathVariable long screenId) {
        List<ShowtimeResponse> showtimes = showtimeService.findByScreen(screenId).stream()
                .map(ShowtimeResponse::from)
                .toList();
        log.info("GET /showtimes/screen/{} -> {} showtimes", screenId, showtimes.size());
        return showtimes;
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable long id) {
        showtimeService.delete(id);
    }
}
