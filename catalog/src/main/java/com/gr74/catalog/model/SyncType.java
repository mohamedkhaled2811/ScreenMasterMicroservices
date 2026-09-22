package com.gr74.catalog.model;

/**
 * The TMDB endpoints the sync tracks. List types use a page cursor; {@link #CHANGES} uses a date cursor.
 */
public enum SyncType {

    POPULAR("/movie/popular", true),
    TOP_RATED("/movie/top_rated", true),
    NOW_PLAYING("/movie/now_playing", true),
    /** The incremental change feed — date-cursor driven. */
    CHANGES("/movie/changes", false);

    private final String path;
    private final boolean backfillList;

    SyncType(String path, boolean backfillList) {
        this.path = path;
        this.backfillList = backfillList;
    }

    /** The TMDB endpoint path for this type (relative to the configured base URL). */
    public String path() {
        return path;
    }

    /** True for the paged list types; false for the {@link #CHANGES} feed. */
    public boolean isBackfillList() {
        return backfillList;
    }
}
