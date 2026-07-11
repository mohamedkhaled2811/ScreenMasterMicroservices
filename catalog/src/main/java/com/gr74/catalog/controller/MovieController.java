package com.gr74.catalog.controller;

import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.gr74.catalog.controller.dto.MovieFilter;
import com.gr74.catalog.dto.MovieDto;
import com.gr74.catalog.dto.MovieSummaryDto;
import com.gr74.catalog.exception.ApiError;
import com.gr74.catalog.model.Movie;
import com.gr74.catalog.service.MovieService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springdoc.core.annotations.ParameterObject;

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
@Tag(name = "Movies", description = "Browse and search the movie catalogue.")
public class MovieController {

    private final MovieService movieService;

    @GetMapping("/{id}")
    @Operation(summary = "Get a movie by id", description = "Returns full detail for one movie.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The movie."),
            @ApiResponse(responseCode = "404", description = "No movie with that id. code = CATALOG_MOVIE_NOT_FOUND.",
                    content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "400", description = "Malformed id. code = CATALOG_VALIDATION_ERROR.",
                    content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ApiError.class)))
    })
    public MovieDto getMovie(@Parameter(description = "Movie id.", example = "603") @PathVariable Long id) {
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
    @Operation(
            summary = "Search movies (paged)",
            description = """
                    Paged, dynamically-filtered search. Any subset of the filter query params narrows the
                    result (AND-combined); page/size/sort ride on the standard Pageable params (0-indexed,
                    default size 20 sorted by popularity DESC, size hard-capped at 100). The response is
                    the PagedModel envelope: { content: [...], page: { size, number, totalElements,
                    totalPages } }.""")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "A page of matching movies (PagedModel envelope)."),
            @ApiResponse(responseCode = "400", description = "A filter param is out of range or the sort field is not whitelisted. code = CATALOG_VALIDATION_ERROR.",
                    content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ApiError.class)))
    })
    public Page<MovieSummaryDto> searchMovies(
            @Valid @ParameterObject MovieFilter filter,
            @ParameterObject @PageableDefault(size = 20, sort = "popularity", direction = Sort.Direction.DESC) Pageable pageable) {
        Page<MovieSummaryDto> page = movieService.search(filter, pageable).map(MovieSummaryDto::from);
        log.info("GET /movies -> {} of {} match", page.getNumberOfElements(), page.getTotalElements());
        return page;
    }

    /**
     * Batch-resolve a known set of ids to their {@link MovieSummaryDto}s in one call. This is the
     * cross-service composition helper: Booking's "my bookings" endpoint holds a page of bookings, each
     * carrying a {@code movieId}, and needs the titles — one {@code GET /movies/batch?ids=…} instead of
     * N per-id calls (the network N+1 that naive composition falls into).
     *
     * <p><b>Why this isn't paginated</b> even though the "listings paginate, never dump" rule normally
     * forbids a bare array: this is not a listing/browse — the caller already <em>holds the exact keys</em>
     * and wants them resolved, the way a {@code WHERE id IN (…)} does. There's nothing to page <em>through</em>.
     * The unbounded-dump risk is instead contained by a hard cap (max 100 ids, enforced in the service),
     * so a caller still can't ask for the whole catalogue this way. Ids we don't have are simply omitted
     * (no 404) — the caller merges by id and treats a miss as an unknown title.
     */
    @GetMapping("/batch")
    @Operation(
            summary = "Resolve movies by a set of ids",
            description = """
                    Returns the movies for the given ids as a plain array (not paginated — the caller
                    already holds the exact ids). Used by sibling services to resolve a known id set to
                    titles in one round-trip. Capped at 100 ids per call; ids not found are omitted.""")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The movies for the ids we hold (a plain array; missing ids omitted)."),
            @ApiResponse(responseCode = "400", description = "More than 100 ids requested. code = CATALOG_VALIDATION_ERROR.",
                    content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ApiError.class)))
    })
    public List<MovieSummaryDto> batchByIds(
            @Parameter(description = "Movie ids to resolve, comma-separated. Max 100.", example = "603,550,27205")
            @RequestParam List<Long> ids) {
        List<MovieSummaryDto> movies = movieService.batchByIds(ids).stream()
                .map(MovieSummaryDto::from)
                .toList();
        log.info("GET /movies/batch -> resolved {} of {} requested id(s)", movies.size(), ids.size());
        return movies;
    }
}
