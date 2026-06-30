package com.gr74.catalog.model;

/**
 * The TMDB endpoints the sync tracks. The three list types ({@link #POPULAR}, {@link #TOP_RATED},
 * {@link #NOW_PLAYING}) drive the one-time <em>backfill</em>: each maps to a list endpoint and is
 * paged and resumed independently via its own page cursor. {@link #CHANGES} is different — it tracks
 * the ongoing <em>incremental refresh</em> ({@code /movie/changes}) and uses a <em>date</em> cursor
 * ({@code lastChangesSyncedDate}) rather than the page cursor; its {@code lastPage}/{@code totalPages}
 * fields stay unused. The sync keeps one {@link SyncStatus} row per constant.
 *
 * <p>Persisted as a {@code STRING} (never ordinal) so reordering or inserting a constant can't
 * silently corrupt existing {@code sync_status} rows — a project convention, and exactly the
 * fragile-enum trap the schema doc flags in the monolith.
 */
public enum SyncType {

    POPULAR("/movie/popular", true),
    TOP_RATED("/movie/top_rated", true),
    NOW_PLAYING("/movie/now_playing", true),
    /** The incremental change feed — date-cursor driven, not part of the page-walk backfill. */
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

    /** True for the paged list types the backfill walks; false for the {@link #CHANGES} feed. */
    public boolean isBackfillList() {
        return backfillList;
    }
}
