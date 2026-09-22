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
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MovieService {

    /** Sortable fields for {@code GET /movies}. Unknown fields are rejected as 400. */
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

    /** Page through movies matching the (possibly empty) filter. */
    @Transactional(readOnly = true)
    public Page<Movie> search(MovieFilter filter, Pageable pageable) {
        validateSort(pageable.getSort());
        log.info("Searching movies: filter={}, page={}, size={}, sort={}",
                filter, pageable.getPageNumber(), pageable.getPageSize(), pageable.getSort());
        return movieRepository.findMoviePage(MovieSpecifications.from(filter), pageable);
    }

    /**
     * Resolve a known set of ids in one query. Capped at 100; missing ids are omitted.
     */
    @Transactional(readOnly = true)
    public List<Movie> batchByIds(Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        // De-dup before counting and querying.
        List<Long> distinct = ids.stream().distinct().toList();
        if (distinct.size() > WebPagingConfig.MAX_PAGE_SIZE) {
            throw new CatalogException(CatalogErrorCode.CATALOG_VALIDATION_ERROR,
                    "Too many ids: " + distinct.size() + " requested, max " + WebPagingConfig.MAX_PAGE_SIZE
                            + " per batch.");
        }
        log.info("Batch movie lookup for {} distinct id(s)", distinct.size());
        return movieRepository.findWithGenresByIdIn(distinct);
    }

    /** Reject any sort property not in the whitelist. */
    private void validateSort(Sort sort) {
        for (Sort.Order order : sort) {
            if (!SORTABLE_FIELDS.contains(order.getProperty())) {
                throw new CatalogException(CatalogErrorCode.CATALOG_VALIDATION_ERROR,
                        "Cannot sort by '" + order.getProperty() + "'. Sortable fields: " + SORTABLE_FIELDS);
            }
        }
    }
}
