package com.gr74.catalog.service;

import java.util.Set;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.gr74.catalog.controller.dto.MovieFilter;
import com.gr74.catalog.exception.CatalogErrorCode;
import com.gr74.catalog.exception.CatalogException;
import com.gr74.catalog.exception.MovieNotFoundException;
import com.gr74.catalog.model.Movie;
import com.gr74.catalog.repository.MovieRepository;
import com.gr74.catalog.repository.spec.MovieSpecifications;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Read-side business logic for the movie catalogue.
 *
 * <p>{@link #getById(Long)} runs in a read-only transaction so the repository's genres-fetch happens
 * inside an open session (we run with {@code open-in-view: false}); the mapper to a DTO can then read
 * {@code genres} without a {@code LazyInitializationException}. A missing movie is a coded domain
 * error ({@link MovieNotFoundException} → 404), not a null the controller has to special-case.
 *
 * <p>{@link #search(MovieFilter, Pageable)} powers the dynamic {@code GET /movies} filter: it turns
 * the filter into a composed {@code Specification} and runs it paged. The {@code Pageable}'s sort is
 * validated against {@link #SORTABLE_FIELDS} <em>before</em> the query so an arbitrary or injected
 * sort field is a coded 400, never a leaked persistence error.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MovieService {

    /**
     * The only entity properties a caller may sort by. A whitelist (not a free-for-all) keeps the
     * sort clause safe and the contract explicit — an unknown field is rejected as a validation error
     * rather than passed to Hibernate, which would surface as an opaque 500.
     */
    private static final Set<String> SORTABLE_FIELDS =
            Set.of("title", "releaseDate", "voteAverage", "popularity");

    private final MovieRepository movieRepository;

    @Transactional(readOnly = true)
    public Movie getById(Long id) {
        return movieRepository.findById(id)
                .orElseThrow(() -> {
                    log.warn("Movie not found for id={}", id);
                    return new MovieNotFoundException(id);
                });
    }

    /**
     * Page through movies matching the (possibly empty) filter. Returns entities with their
     * {@code genres} fetched (the repository's two-step page); the controller maps each to a
     * {@code MovieSummaryDto}.
     */
    @Transactional(readOnly = true)
    public Page<Movie> search(MovieFilter filter, Pageable pageable) {
        validateSort(pageable.getSort());
        log.info("Searching movies: filter={}, page={}, size={}, sort={}",
                filter, pageable.getPageNumber(), pageable.getPageSize(), pageable.getSort());
        return movieRepository.findMoviePage(MovieSpecifications.from(filter), pageable);
    }

    /** Reject any requested sort property not in the whitelist as a coded validation error. */
    private void validateSort(Sort sort) {
        for (Sort.Order order : sort) {
            if (!SORTABLE_FIELDS.contains(order.getProperty())) {
                throw new CatalogException(CatalogErrorCode.CATALOG_VALIDATION_ERROR,
                        "Cannot sort by '" + order.getProperty() + "'. Sortable fields: " + SORTABLE_FIELDS);
            }
        }
    }
}
