package com.gr74.booking.service;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.gr74.booking.client.CatalogClient.MovieProjectionData;
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
            backfiller.backfill(id).ifPresent(movie -> resolved.put(id, movie.title()));
        }
        log.info("Way-B titles: {}/{} resolved ({} local hits, {} backfilled)",
                resolved.size(), ids.size(), localHits, resolved.size() - localHits);
        return resolved;
    }

    /**
     * Resolve ONE movie id to the title + poster the ticket email needs, backfilling a miss.
     *
     * <p>Used on the booking-confirm path, where the facts are snapshotted onto the
     * {@code BookingConfirmed} event. Reading them here — from Booking's own read model — is precisely
     * what keeps Notification free of a synchronous Catalog call at send time: the confirm pays for the
     * lookup once (usually a local row read), and the event carries the answer forever after.
     *
     * <p><b>Returns empty rather than throwing when the movie cannot be resolved</b>, and the caller must
     * treat that as "send the ticket without the title/poster". A confirmed booking is money that has
     * already changed hands; failing the confirm — or the email — because a decorative poster could not
     * be looked up would be letting the least important dependency veto the most important outcome.
     */
    @Transactional(readOnly = true)
    public Optional<MovieProjectionData> movieById(Long id) {
        if (id == null) {
            return Optional.empty();
        }
        return movieProjectionRepository.findById(id)
                .map(cached -> new MovieProjectionData(cached.getTitle(), cached.getPosterPath()))
                .or(() -> backfiller.backfill(id));
    }
}
