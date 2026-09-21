package com.gr74.booking.controller;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
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
import com.gr74.booking.exception.ApiError;
import com.gr74.booking.service.ShowtimeService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * REST surface for showtimes — the Scheduling context absorbed into Booking.
 *
 * <p>Bare paths ({@code /showtimes/...}); the gateway strips the {@code /api} prefix. Reads mirror the
 * monolith's showtime endpoints (by id, by movie, upcoming-by-movie, by screen). {@code POST /showtimes}
 * validates the movie against Catalog synchronously — a bad {@code movieId} comes back
 * as {@code BOOKING_MOVIE_NOT_FOUND} (404) and a Catalog outage as {@code BOOKING_CATALOG_UNAVAILABLE}
 * (503), both rendered by {@code GlobalExceptionHandler}. DTOs cross the wire, not entities.
 *
 * <p>A {@link Clock} is injected (rather than calling {@code LocalDate.now()} directly) so "upcoming"
 * has a testable notion of "today" — a test can pin the clock.
 *
 * <p><b>Authorization:</b> reads need any authenticated token; creating or
 * deleting a showtime needs the {@code ADMIN} realm role ({@code @PreAuthorize} below — the check
 * lives next to the thing it protects). The coarse "authenticated by default" rule is in
 * {@code SecurityConfig}.
 */
@Slf4j
@RestController
@RequestMapping("/showtimes")
@RequiredArgsConstructor
@Tag(name = "Showtimes", description = "Schedule and read showtimes. Reads return plain arrays, not the paged envelope.")
public class ShowtimeController {

    private final ShowtimeService showtimeService;
    private final Clock clock;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Create a showtime",
            description = "Schedules a showtime on a screen for a movie. The movieId is validated live against the Catalog service (this service does not own movies).")
    @ApiResponse(responseCode = "201", description = "Showtime created.")
    @ApiResponse(responseCode = "400", description = "Invalid body (e.g. bad time/price). code = BOOKING_VALIDATION_ERROR.",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "404", description = "The screen does not exist, or Catalog reports the movieId does not exist. code = BOOKING_SCREEN_NOT_FOUND / BOOKING_MOVIE_NOT_FOUND.",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "503", description = "Catalog could not be reached to validate the movie — retryable. code = BOOKING_CATALOG_UNAVAILABLE.",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ApiError.class)))
    public ShowtimeResponse create(@Valid @RequestBody CreateShowtimeRequest request) {
        return ShowtimeResponse.from(showtimeService.create(request));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get a showtime by id")
    @ApiResponse(responseCode = "200", description = "The showtime.")
    @ApiResponse(responseCode = "404", description = "No such showtime. code = BOOKING_SHOWTIME_NOT_FOUND.",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ApiError.class)))
    public ShowtimeResponse getById(@PathVariable long id) {
        ShowtimeResponse response = ShowtimeResponse.from(showtimeService.getById(id));
        log.info("GET /showtimes/{} -> movieId={}", id, response.movieId());
        return response;
    }

    @GetMapping("/movie/{movieId}")
    @Operation(summary = "List all showtimes for a movie",
            description = "Returns a plain array (not paged). Empty array if the movie has no showtimes.")
    @ApiResponse(responseCode = "200", description = "The movie's showtimes.")
    public List<ShowtimeResponse> byMovie(@PathVariable long movieId) {
        List<ShowtimeResponse> showtimes = showtimeService.findByMovie(movieId).stream()
                .map(ShowtimeResponse::from)
                .toList();
        log.info("GET /showtimes/movie/{} -> {} showtimes", movieId, showtimes.size());
        return showtimes;
    }

    @GetMapping("/movie/upcoming/{movieId}")
    @Operation(summary = "List upcoming showtimes for a movie",
            description = "Showtimes from today onward for the movie, as a plain array (not paged).")
    @ApiResponse(responseCode = "200", description = "The movie's upcoming showtimes.")
    public List<ShowtimeResponse> upcomingByMovie(@PathVariable long movieId) {
        LocalDate today = LocalDate.now(clock);
        List<ShowtimeResponse> showtimes = showtimeService.findUpcomingByMovie(movieId, today).stream()
                .map(ShowtimeResponse::from)
                .toList();
        log.info("GET /showtimes/movie/upcoming/{} (from {}) -> {} showtimes", movieId, today, showtimes.size());
        return showtimes;
    }

    @GetMapping("/screen/{screenId}")
    @Operation(summary = "List all showtimes on a screen",
            description = "Returns a plain array (not paged). Empty array if the screen has no showtimes.")
    @ApiResponse(responseCode = "200", description = "The screen's showtimes.")
    public List<ShowtimeResponse> byScreen(@PathVariable long screenId) {
        List<ShowtimeResponse> showtimes = showtimeService.findByScreen(screenId).stream()
                .map(ShowtimeResponse::from)
                .toList();
        log.info("GET /showtimes/screen/{} -> {} showtimes", screenId, showtimes.size());
        return showtimes;
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Delete a showtime")
    @ApiResponse(responseCode = "204", description = "Deleted.")
    @ApiResponse(responseCode = "404", description = "No such showtime. code = BOOKING_SHOWTIME_NOT_FOUND.",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ApiError.class)))
    public void delete(@PathVariable long id) {
        showtimeService.delete(id);
    }
}
