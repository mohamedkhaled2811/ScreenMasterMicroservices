package com.gr74.catalog.model;

/**
 * The TMDB movie lists the sync walks. Each maps to a TMDB list endpoint path; the sync keeps one
 * {@link SyncStatus} row per type so each list is paged and resumed independently.
 *
 * <p>Persisted as a {@code STRING} (never ordinal) so reordering or inserting a constant can't
 * silently corrupt existing {@code sync_status} rows — a project convention, and exactly the
 * fragile-enum trap the schema doc flags in the monolith.
 */
public enum SyncType {

    POPULAR("/movie/popular"),
    TOP_RATED("/movie/top_rated"),
    NOW_PLAYING("/movie/now_playing");

    private final String path;

    SyncType(String path) {
        this.path = path;
    }

    /** The TMDB list-endpoint path for this type (relative to the configured base URL). */
    public String path() {
        return path;
    }
}
