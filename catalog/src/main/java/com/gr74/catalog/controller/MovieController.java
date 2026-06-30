package com.gr74.catalog.controller;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.gr74.catalog.controller.dto.MovieFilter;
import com.gr74.catalog.dto.MovieDto;
import com.gr74.catalog.dto.MovieSummaryDto;
import com.gr74.catalog.model.Movie;
import com.gr74.catalog.service.MovieService;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * The catalogue's read endpoints.
 *
 * <p>{@code GET /movies/{id}} returns a {@link MovieDto} (full detail); {@code GET /movies} returns a
 * paged, dynamically-filtered list of lean {@link MovieSummaryDto}s. The service exposes the
 * <em>bare</em> {@code /movies} path — the {@code /api} namespace lives only at the gateway, which
 * strips it via {@code StripPrefix=1} before forwarding ({@code /api/movies} → {@code /movies}). So
 * this matches how {@code payment} exposes {@code /payments}; no edge concern leaks into the service.
 *
 * <p>A missing movie, a malformed id, or an out-of-range/unknown filter or sort param is thrown and
 * translated to an RFC 9457 {@code ProblemDetail} by {@code GlobalExceptionHandler}; these methods
 * describe only the happy path. DTOs cross the wire, not the {@link Movie} entity (project convention).
 */
@Slf4j
@RestController
@RequestMapping("/movies")
@RequiredArgsConstructor
public class MovieController {

    private final MovieService movieService;

    @GetMapping("/{id}")
    public MovieDto getMovie(@PathVariable Long id) {
        Movie movie = movieService.getById(id);
        log.info("GET /movies/{} -> {}", id, movie.getTitle());
        return MovieDto.from(movie);
    }

    /**
     * Dynamic, paged movie search. Any subset of the {@link MovieFilter} query params filters the
     * result ({@code AND}-combined); {@code page}/{@code size}/{@code sort} ride on the
     * {@link Pageable}. Defaults to 20 per page sorted by popularity (descending) when the caller
     * sends no paging hints, so "browse the catalogue" is a bare {@code GET /movies}.
     *
     * <p>{@code @Valid} triggers the bean-validation constraints on {@code MovieFilter} (e.g. a rating
     * outside {@code [0,10]}); the sort whitelist is enforced in the service. Both failure modes render
     * as a {@code CATALOG_VALIDATION_ERROR} ProblemDetail.
     */
    @GetMapping
    public Page<MovieSummaryDto> searchMovies(
            @Valid MovieFilter filter,
            @PageableDefault(size = 20, sort = "popularity", direction = Sort.Direction.DESC) Pageable pageable) {
        Page<MovieSummaryDto> page = movieService.search(filter, pageable).map(MovieSummaryDto::from);
        log.info("GET /movies -> {} of {} match", page.getNumberOfElements(), page.getTotalElements());
        return page;
    }
}
