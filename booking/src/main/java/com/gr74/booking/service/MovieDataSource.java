package com.gr74.booking.service;

/**
 * Which strategy {@code GET /bookings/my} uses to resolve each booking's movie title,
 * selectable per request via {@code ?source=}.
 */
public enum MovieDataSource {

    /** Resolve titles live from Catalog in one batch call; degrades to null on outage. */
    COMPOSITION,

    /**
     * Resolve titles from the local {@code movie_projections} cache, backfilling misses.
     * Serves cached titles during a Catalog outage; renames lag until the event lands.
     */
    READMODEL
}
