package com.gr74.catalog.service;

import java.util.Collection;
import java.util.List;
import java.util.Set;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.gr74.catalog.config.WebPagingConfig;
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

    /**
     * Resolve a known set of movie ids to their (genre-loaded) entities in one query — the read side of
     * {@code GET /movies/batch}. Unlike {@link #search}, this is a <em>bounded exact-key lookup</em>, not
     * an open listing: the caller (Booking's "my bookings" composition) already holds the ids and wants
     * them all at once, so it isn't paginated. But "not paginated" can't mean "unbounded" — a caller
     * could still send thousands of ids — so we cap the set at {@link WebPagingConfig#MAX_PAGE_SIZE}
     * (the same ceiling a single page could return) and reject an over-large request as a coded
     * validation error rather than letting it degenerate into a huge {@code IN (…)}.
     *
     * <p>Ids not found are simply absent from the result (no 404) — a batch resolve tolerates gaps,
     * because the caller merges by id and treats a miss as "title unknown".
     */
    @Transactional(readOnly = true)
    public List<Movie> batchByIds(Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        // De-dup before counting and querying — repeated ids shouldn't count against the cap or the IN.
        List<Long> distinct = ids.stream().distinct().toList();
        if (distinct.size() > WebPagingConfig.MAX_PAGE_SIZE) {
            throw new CatalogException(CatalogErrorCode.CATALOG_VALIDATION_ERROR,
                    "Too many ids: " + distinct.size() + " requested, max " + WebPagingConfig.MAX_PAGE_SIZE
                            + " per batch.");
        }
        log.info("Batch movie lookup for {} distinct id(s)", distinct.size());
        return movieRepository.findWithGenresByIdIn(distinct);
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
