package com.gr74.booking.service;

import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.gr74.booking.client.CatalogClient;
import com.gr74.booking.client.CatalogClient.MovieProjectionData;
import com.gr74.booking.exception.CatalogUnavailableException;
import com.gr74.booking.model.MovieProjection;
import com.gr74.booking.repository.MovieProjectionRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Lazy-backfills one missing title from Catalog into the {@code movie_projections} read model.
 * Runs in a fresh read-write transaction (REQUIRES_NEW) in a separate bean so the
 * caller's read-only transaction does not swallow the write. Persists only real titles.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MovieBackfiller {

    private final MovieProjectionRepository movieProjectionRepository;
    private final CatalogClient catalogClient;

    /**
     * Fetch one missing movie from Catalog and cache it.
     * @return the cached projection; empty on 404 or Catalog outage (neither is cached)
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<MovieProjectionData> backfill(Long id) {
        try {
            Optional<MovieProjectionData> movie = catalogClient.projectionById(id);
            // Cache only a real result; updatedAt stays null so the first real event wins.
            movie.ifPresent(m -> movieProjectionRepository.save(
                    new MovieProjection(id, m.title(), m.posterPath(), null)));
            return movie;
        } catch (CatalogUnavailableException outage) {
            // Serve null this once without caching; the next read retries.
            log.warn("Lazy backfill for movieId={} hit an unavailable Catalog; serving null (not caching): {}",
                    id, outage.getMessage());
            return Optional.empty();
        }
    }
}
