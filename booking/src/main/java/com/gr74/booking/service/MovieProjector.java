package com.gr74.booking.service;

import java.time.Instant;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.gr74.booking.messaging.MovieUpsertedEvent;
import com.gr74.booking.model.MovieProjection;
import com.gr74.booking.repository.MovieProjectionRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Applies {@code MovieUpserted} events to the {@code movie_projections} read model.
 * Idempotent by the movie-id primary key; stale events (not strictly newer) are dropped.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MovieProjector {

    private final MovieProjectionRepository movieProjectionRepository;

    /**
     * Upsert the title for {@code event.id()}, unless a newer-or-equal row is already stored.
     * @return true if written, false if dropped as stale
     */
    @Transactional
    public boolean apply(MovieUpsertedEvent event) {
        MovieProjection existing = movieProjectionRepository.findById(event.id()).orElse(null);

        if (existing != null && !isNewer(event.updatedAt(), existing.getUpdatedAt())) {
            log.debug("Dropping stale MovieUpserted id={} (event updatedAt={} <= stored {})",
                    event.id(), event.updatedAt(), existing.getUpdatedAt());
            return false;
        }

        if (existing == null) {
            movieProjectionRepository.save(new MovieProjection(
                    event.id(), event.title(), event.posterPath(), event.updatedAt()));
        } else {
            existing.apply(event.title(), event.posterPath(), event.updatedAt());
            movieProjectionRepository.save(existing);
        }
        log.debug("Applied MovieUpserted id={} title='{}' updatedAt={}",
                event.id(), event.title(), event.updatedAt());
        return true;
    }

    /** Null stored timestamp (backfilled row) always loses; null incoming never wins. */
    private static boolean isNewer(Instant incoming, Instant stored) {
        if (stored == null) {
            return true;
        }
        if (incoming == null) {
            return false;
        }
        return incoming.isAfter(stored);
    }
}
