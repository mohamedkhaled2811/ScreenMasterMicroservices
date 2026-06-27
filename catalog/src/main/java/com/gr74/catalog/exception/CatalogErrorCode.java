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
