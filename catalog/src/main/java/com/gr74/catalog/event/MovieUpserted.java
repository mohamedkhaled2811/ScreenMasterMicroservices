package com.gr74.catalog.event;

import java.time.Instant;

/**
 * Published when a movie row is written on the incremental-refresh path.
 * Emitted only from {@code CatalogUpserter.refreshMovies}, never from backfill.
 */
public record MovieUpserted(
        Long id,
        String title,
        String posterPath,
        Instant updatedAt,
        String eventId) {
}
