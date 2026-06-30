package com.gr74.catalog.exception;

/**
 * Thrown when a TMDB upstream call fails — a network error, a non-2xx response, or an unparseable
 * body. Maps to {@link CatalogErrorCode#CATALOG_TMDB_SYNC_ERROR} (HTTP 502: our dependency failed,
 * not us).
 *
 * <p>The scheduled sync catches this, records the sync as {@code FAILED} at its current page, and
 * lets the next tick resume — so a transient TMDB hiccup never corrupts our data or loses progress.
 */
public class TmdbSyncException extends CatalogException {

    public TmdbSyncException(String message, Throwable cause) {
        super(CatalogErrorCode.CATALOG_TMDB_SYNC_ERROR, message, cause);
    }

    public TmdbSyncException(String message) {
        super(CatalogErrorCode.CATALOG_TMDB_SYNC_ERROR, message);
    }
}
