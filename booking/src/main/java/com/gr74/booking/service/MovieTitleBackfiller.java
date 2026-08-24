package com.gr74.booking.service;

import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.gr74.booking.client.CatalogClient;
import com.gr74.booking.exception.CatalogUnavailableException;
import com.gr74.booking.model.MovieTitle;
import com.gr74.booking.repository.MovieTitleRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Lazy-backfills a single missing title from Catalog into the {@code movie_titles} read model.
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
public class MovieTitleBackfiller {

    private final MovieTitleRepository movieTitleRepository;
    private final CatalogClient catalogClient;

    /**
     * Fetch one missing title from Catalog and cache it in its own read-write transaction.
     *
     * @return the title on success; empty on a 404 or a Catalog outage (neither is cached)
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<String> backfill(Long id) {
        try {
            Optional<String> title = catalogClient.titleById(id);
            // Cache ONLY a real title. updatedAt is null: this row came from a fetch, not an event, so it
            // has no ordering baseline — the first real MovieUpserted (any timestamp) will win.
            title.ifPresent(t -> movieTitleRepository.save(new MovieTitle(id, t, null)));
            return title;
        } catch (CatalogUnavailableException outage) {
            // Serve null this once; DO NOT write anything. The miss retries on the next read.
            log.warn("Lazy backfill for movieId={} hit an unavailable Catalog; serving null (not caching): {}",
                    id, outage.getMessage());
            return Optional.empty();
        }
    }
}
