package com.gr74.catalog.exception;

import org.springframework.http.HttpStatus;

/**
 * Machine-readable error codes. Each carries the HTTP status it maps to.
 */
public enum CatalogErrorCode {

    /** A request parameter failed validation (e.g. a malformed movie id). */
    CATALOG_VALIDATION_ERROR(HttpStatus.BAD_REQUEST, "Validation failed"),

    /** No movie exists for the requested id. */
    CATALOG_MOVIE_NOT_FOUND(HttpStatus.NOT_FOUND, "Movie not found"),

    /**
     * No (or an invalid) Bearer token. Rendered by the security filter chain via
     * {@code SecurityProblemSupport}.
     */
    CATALOG_UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "Authentication required"),

    /** A valid token without the required role. */
    CATALOG_FORBIDDEN(HttpStatus.FORBIDDEN, "Access denied"),

    /** The TMDB upstream call failed. Maps to 502. */
    CATALOG_TMDB_SYNC_ERROR(HttpStatus.BAD_GATEWAY, "TMDB sync failed"),

    /** A fallback for anything we did not anticipate — never leak internals to the caller. */
    CATALOG_INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "Internal error");

    private final HttpStatus status;
    private final String title;

    CatalogErrorCode(HttpStatus status, String title) {
        this.status = status;
        this.title = title;
    }

    public HttpStatus status() {
        return status;
    }

    /** Short, human-readable summary used as the {@code ProblemDetail} title. */
    public String title() {
        return title;
    }
}
