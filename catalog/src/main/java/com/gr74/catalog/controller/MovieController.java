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
 * Movie read endpoints: {@code GET /movies/{id}}, paged {@code GET /movies}, and {@code GET /movies/batch}.
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
     * Dynamic, paged movie search. Any subset of filter params applies (AND-combined).
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
     * Resolve a known set of ids in one call. Not paginated; capped at 100 ids.
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
