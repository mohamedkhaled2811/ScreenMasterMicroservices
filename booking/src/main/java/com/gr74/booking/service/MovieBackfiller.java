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
 * Lazy-backfills a single missing title from Catalog into the {@code movie_projections} read model.
 *
 * <p>{@link Propagation#REQUIRES_NEW} suspends the caller's read-only transaction and runs the write in a
 * fresh read-write one, so the row actually lands. It lives in a <em>separate</em> bean because a
 * {@code REQUIRES_NEW} method called from within the same class would bypass Spring's proxy
 * (self-invocation) and silently inherit the caller's transaction — the same trap {@code CatalogUpserter}
 * documents on the Catalog side.
 *
 * <p><b>Cache-poisoning guard.</b> We persist only when a real title comes back. A 404 caches nothing
 * (there is nothing to cache); a Catalog outage caches nothing either, so the miss is retried on the next
 * read once Catalog recovers instead of a placeholder row permanently hiding it.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MovieBackfiller {

    private final MovieProjectionRepository movieProjectionRepository;
    private final CatalogClient catalogClient;

    /**
     * Fetch one missing movie from Catalog and cache it in its own read-write transaction.
     *
     * <p>Fills BOTH projected columns (title and poster path) from the single fetch: the poster is what
     * the ticket email renders, and backfilling it separately would double the network cost of a miss.
     *
     * @return the cached projection on success; empty on a 404 or a Catalog outage (neither is cached)
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<MovieProjectionData> backfill(Long id) {
        try {
            Optional<MovieProjectionData> movie = catalogClient.projectionById(id);
            // Cache ONLY a real result. updatedAt is null: this row came from a fetch, not an event, so it
            // has no ordering baseline — the first real MovieUpserted (any timestamp) will win.
            movie.ifPresent(m -> movieProjectionRepository.save(
                    new MovieProjection(id, m.title(), m.posterPath(), null)));
            return movie;
        } catch (CatalogUnavailableException outage) {
            // Serve null this once; DO NOT write anything. The miss retries on the next read.
            log.warn("Lazy backfill for movieId={} hit an unavailable Catalog; serving null (not caching): {}",
                    id, outage.getMessage());
            return Optional.empty();
        }
    }
}
