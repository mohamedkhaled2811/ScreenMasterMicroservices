package com.gr74.catalog.exception;

import org.springframework.http.HttpStatus;

/**
 * The machine-readable error contract for this service.
 *
 * <p>Every error response carries one of these constants as the {@code code} property of an RFC 9457
 * {@code ProblemDetail} (see {@link GlobalExceptionHandler}). The {@code code} — not the HTTP status,
 * not the human-readable {@code detail} — is what a <em>sibling</em> service (Booking, when it
 * composes movie titles in Phase 2) branches on: the wording of a message may change, but the enum
 * name is a stable promise. Adding a constant is backwards-compatible; renaming one is breaking.
 *
 * <p>Each constant pins the HTTP status it maps to so the status and the code can never drift apart.
 */
public enum CatalogErrorCode {

    /** A request parameter failed validation (e.g. a malformed movie id). */
    CATALOG_VALIDATION_ERROR(HttpStatus.BAD_REQUEST, "Validation failed"),

    /** No movie exists for the requested id. */
    CATALOG_MOVIE_NOT_FOUND(HttpStatus.NOT_FOUND, "Movie not found"),

    /**
     * No (or an invalid) Bearer token. Rendered by the security filter chain — which runs before
     * any controller, so {@code GlobalExceptionHandler} can never see these — via
     * {@code SecurityProblemSupport}, in the same ProblemDetail shape with the same flat
     * {@code code}. Kept distinct from {@link #CATALOG_FORBIDDEN} on purpose: "log in" and "ask an
     * admin" are different answers.
     */
    CATALOG_UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "Authentication required"),

    /**
     * A valid token without the role the endpoint needs (e.g. a {@code USER} calling an
     * {@code ADMIN} method). Same filter-chain rendering as {@link #CATALOG_UNAUTHORIZED}.
     */
    CATALOG_FORBIDDEN(HttpStatus.FORBIDDEN, "Access denied"),

    /**
     * The TMDB upstream call failed (network error, non-2xx, or unparseable body). Maps to 502 —
     * <em>we</em> didn't fail, our dependency did. Surfaces only on the (future) sync-status endpoint
     * or in logs; the scheduled sync catches it and records FAILED for a later resume.
     */
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
