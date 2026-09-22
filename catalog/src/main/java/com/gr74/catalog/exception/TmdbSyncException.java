package com.gr74.catalog.exception;

/** Thrown when a TMDB call fails. Maps to 502. */
public class TmdbSyncException extends CatalogException {

    public TmdbSyncException(String message, Throwable cause) {
        super(CatalogErrorCode.CATALOG_TMDB_SYNC_ERROR, message, cause);
    }

    public TmdbSyncException(String message) {
        super(CatalogErrorCode.CATALOG_TMDB_SYNC_ERROR, message);
    }
}
