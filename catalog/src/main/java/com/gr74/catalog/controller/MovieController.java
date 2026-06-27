package com.gr74.catalog.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.gr74.catalog.dto.MovieDto;
import com.gr74.catalog.model.Movie;
import com.gr74.catalog.service.MovieService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * The catalogue's read endpoint.
 *
 * <p>{@code GET /movies/{id}} returns a {@link MovieDto}. The service exposes the <em>bare</em>
 * {@code /movies} path — the {@code /api} namespace lives only at the gateway, which strips it via
 * {@code StripPrefix=1} before forwarding ({@code /api/movies/{id}} → {@code /movies/{id}}). So this
 * matches how {@code payment} exposes {@code /payments}; no edge concern leaks into the service.
 *
 * <p>A missing movie or a malformed id is thrown and translated to an RFC 9457 {@code ProblemDetail}
 * by {@code GlobalExceptionHandler}; this method only describes the happy path. DTOs cross the wire,
 * not the {@link Movie} entity (project convention).
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
}
