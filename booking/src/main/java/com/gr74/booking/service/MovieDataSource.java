package com.gr74.booking.service;

/**
 * Which strategy {@code GET /bookings/my} uses to resolve each booking's movie title — the two answers to
 * the M2 cross-service-query problem, selectable per request via {@code ?source=} so both are demoable
 * side-by-side (the whole point of BUILD_PLAN 2.4: run them against the same stopped Catalog and watch
 * them diverge). Same {@code MyBookingDto} either way; only the title's provenance differs.
 */
public enum MovieDataSource {

    /**
     * Way A — API composition. Resolve titles live from Catalog over HTTP in one batch call per page.
     * Fresh, but read-time-coupled to Catalog: an outage degrades every title to {@code null}.
     */
    COMPOSITION,

    /**
     * Way B — CQRS read model. Resolve titles by a local join to the {@code movie_projections} cache (kept
     * fresh by {@code MovieUpserted} events), with a one-time lazy backfill on a cache miss. Eventually
     * consistent (a rename lags until the event lands), but a Catalog outage doesn't stop already-cached
     * titles from rendering — that's the resilience way A trades away.
     */
    READMODEL
}
