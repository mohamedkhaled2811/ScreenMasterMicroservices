package com.gr74.booking.service;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.gr74.booking.model.MovieProjection;
import com.gr74.booking.repository.MovieProjectionRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * The <b>read side</b> of way B: resolve a page's movie ids to titles from Booking's own
 * {@code movie_projections} read model, calling Catalog only to <em>lazily backfill</em> a cache miss.
 *
 * <p>Steady state is a single local {@code WHERE id IN (…)} — no Catalog call, which is what lets "my
 * bookings (way B)" keep answering when Catalog is down. The cost of a genuinely-uncached movie is one
 * lazy backfill, and after that it's local forever. This is why way B is network-free only for
 * <em>already-cached</em> movies — the honest caveat the 2.4 demo reads once (while Catalog is up) before
 * stopping Catalog. See {@code docs/concepts/cqrs-read-model.md} and {@code docs/adr/0001-...}.
 *
 * <p><b>The write is delegated on purpose.</b> This method is a query and runs read-only; the backfill's
 * INSERT is therefore handed to {@link MovieBackfiller}, a separate bean whose
 * {@code REQUIRES_NEW} transaction can actually commit. See that class for why an in-class write here
 * would be silently dropped.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MovieReadModel {

    private final MovieProjectionRepository movieProjectionRepository;
    private final MovieBackfiller backfiller;

    /**
     * Resolve {@code ids} to titles, backfilling misses. The returned map has an entry only for ids we
     * could resolve (locally or via a successful backfill); an id absent from the map is "title unknown"
     * to the caller, which renders it as {@code null}.
     */
    @Transactional(readOnly = true)
    public Map<Long, String> titlesByIds(Set<Long> ids) {
        Map<Long, String> resolved = new HashMap<>();
        if (ids == null || ids.isEmpty()) {
            return resolved;
        }

        // 1) The local join — the fast, Catalog-free path that is the whole point of the read model.
        for (MovieProjection cached : movieProjectionRepository.findByIdIn(ids)) {
            resolved.put(cached.getId(), cached.getTitle());
        }
        int localHits = resolved.size();

        // 2) Lazy-backfill each miss exactly once from Catalog, in its own read-write transaction.
        for (Long id : ids) {
            if (resolved.containsKey(id)) {
                continue;
            }
            backfiller.backfill(id).ifPresent(title -> resolved.put(id, title));
        }
        log.info("Way-B titles: {}/{} resolved ({} local hits, {} backfilled)",
                resolved.size(), ids.size(), localHits, resolved.size() - localHits);
        return resolved;
    }
}
