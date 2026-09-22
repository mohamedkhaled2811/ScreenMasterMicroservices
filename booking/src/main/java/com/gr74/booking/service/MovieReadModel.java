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
 * Read side of the movie cache: resolve ids to titles from {@code movie_projections},
 * lazily backfilling misses from Catalog. Writes are delegated to {@link MovieBackfiller}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MovieReadModel {

    private final MovieProjectionRepository movieProjectionRepository;
    private final MovieBackfiller backfiller;

    /**
     * Resolve ids to titles, backfilling misses. Only resolvable ids appear in the map;
     * the caller renders the rest as {@code null}.
     */
    @Transactional(readOnly = true)
    public Map<Long, String> titlesByIds(Set<Long> ids) {
        Map<Long, String> resolved = new HashMap<>();
        if (ids == null || ids.isEmpty()) {
            return resolved;
        }

        // 1) Local lookup first.
        for (MovieProjection cached : movieProjectionRepository.findByIdIn(ids)) {
            resolved.put(cached.getId(), cached.getTitle());
        }
        int localHits = resolved.size();

        // 2) Backfill each miss once from Catalog.
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
     * Resolve one movie id to title + poster for the ticket event. Returns empty when
     * unresolvable; the caller sends the ticket without title/poster rather than failing.
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
