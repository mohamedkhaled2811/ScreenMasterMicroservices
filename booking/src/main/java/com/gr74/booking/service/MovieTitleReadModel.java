package com.gr74.booking.service;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.gr74.booking.client.CatalogClient;
import com.gr74.booking.exception.CatalogUnavailableException;
import com.gr74.booking.model.MovieTitle;
import com.gr74.booking.repository.MovieTitleRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * The <b>read side</b> of way B: resolve a page's movie ids to titles from Booking's own
 * {@code movie_titles} read model, calling Catalog only to <em>lazily backfill</em> a cache miss.
 *
 * <p>Steady state is a single local {@code WHERE id IN (…)} — no Catalog call, which is what lets "my
 * bookings (way B)" keep answering when Catalog is down. The cost of a genuinely-uncached movie is one
 * lazy backfill, and after that it's local forever. This is why way B is network-free only for
 * <em>already-cached</em> movies — the honest caveat the 2.4 demo reads once (while Catalog is up) before
 * stopping Catalog. See {@code docs/concepts/cqrs-read-model.md} and {@code docs/adr/0001-...}.
 *
 * <p><b>Cache-poisoning guard.</b> On a miss we ask {@link CatalogClient#titleById}, which distinguishes
 * "no such movie" (404 → empty) from "Catalog unavailable" (throws). We persist a new
 * {@link MovieTitle} <em>only</em> when a real title comes back. On an outage we catch, log, and serve a
 * {@code null} title <em>this once</em> without writing anything — so the miss is retried on the next
 * read once Catalog recovers, instead of a placeholder row permanently hiding it. On a 404 we also don't
 * cache (nothing to cache) and simply omit the id; a genuinely-unknown movie is rare and re-checking it
 * is cheap.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MovieTitleReadModel {

    private final MovieTitleRepository movieTitleRepository;
    private final CatalogClient catalogClient;

    /**
     * Resolve {@code ids} to titles, backfilling misses. The returned map has an entry only for ids we
     * could resolve (locally or via a successful backfill); an id absent from the map is "title unknown"
     * to the caller, which renders it as {@code null}.
     */
    @Transactional
    public Map<Long, String> titlesByIds(Set<Long> ids) {
        Map<Long, String> resolved = new HashMap<>();
        if (ids == null || ids.isEmpty()) {
            return resolved;
        }

        // 1) The local join — the fast, Catalog-free path that is the whole point of the read model.
        for (MovieTitle cached : movieTitleRepository.findByIdIn(ids)) {
            resolved.put(cached.getId(), cached.getTitle());
        }
        int localHits = resolved.size();

        // 2) Lazy-backfill each miss exactly once from Catalog.
        for (Long id : ids) {
            if (resolved.containsKey(id)) {
                continue;
            }
            backfill(id).ifPresent(title -> resolved.put(id, title));
        }
        log.info("Way-B titles: {}/{} resolved ({} local hits, {} backfilled)",
                resolved.size(), ids.size(), localHits, resolved.size() - localHits);
        return resolved;
    }

    /**
     * Fetch one missing title from Catalog and cache it. Returns the title on success; empty on a 404
     * (nothing to cache) <em>or</em> on a Catalog outage (must not cache — see the class javadoc on
     * poisoning). The two empties differ only in whether we log an outage warning.
     */
    private Optional<String> backfill(Long id) {
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
