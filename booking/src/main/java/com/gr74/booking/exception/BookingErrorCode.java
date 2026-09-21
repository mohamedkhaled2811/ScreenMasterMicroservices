package com.gr74.booking.exception;

import org.springframework.http.HttpStatus;

/**
 * The machine-readable error contract for the booking service.
 *
 * <p>Every error response carries one of these constants as the {@code code} property of an RFC 9457
 * {@code ProblemDetail} (see {@link GlobalExceptionHandler}). The {@code code} — not the HTTP status,
 * not the human-readable {@code detail} — is the stable promise a client or a sibling service branches
 * on. Adding a constant is backwards-compatible; renaming one is a breaking contract change. Mirrors
 * {@code payment}'s {@code PaymentErrorCode} (the reference implementation).
 *
 * <p>Each constant pins the HTTP status it maps to so the status and the code can never drift apart.
 */
public enum BookingErrorCode {

    /** The request body, params, or headers failed validation. */
    BOOKING_VALIDATION_ERROR(HttpStatus.BAD_REQUEST, "Validation failed"),

    /** No theater exists for the requested id. */
    BOOKING_THEATER_NOT_FOUND(HttpStatus.NOT_FOUND, "Theater not found"),

    /** No screen exists for the requested id (or not under the given theater). */
    BOOKING_SCREEN_NOT_FOUND(HttpStatus.NOT_FOUND, "Screen not found"),

    /** No seat exists for the requested id. */
    BOOKING_SEAT_NOT_FOUND(HttpStatus.NOT_FOUND, "Seat not found"),

    /** No seat type exists for the requested id. */
    BOOKING_SEAT_TYPE_NOT_FOUND(HttpStatus.NOT_FOUND, "Seat type not found"),

    /** No showtime exists for the requested id. */
    BOOKING_SHOWTIME_NOT_FOUND(HttpStatus.NOT_FOUND, "Showtime not found"),

    /**
     * No booking exists for the requested id. Payment branches on this: a definitive "no such booking"
     * (404) is a permanent answer, unlike an outage (503) which might resolve on retry.
     */
    BOOKING_NOT_FOUND(HttpStatus.NOT_FOUND, "Booking not found"),

    /**
     * A showtime referenced a {@code movieId} that Catalog does not have. This is the cross-service
     * cut, validated synchronously at showtime-create time — the DB can't enforce it,
     * so the service does.
     */
    BOOKING_MOVIE_NOT_FOUND(HttpStatus.NOT_FOUND, "Movie not found in catalog"),

    /**
     * We could not reach Catalog to validate the {@code movieId} (down, timed out). Distinct from
     * {@link #BOOKING_MOVIE_NOT_FOUND} ("Catalog answered: no such movie") so the caller can tell
     * "try again later" apart from "that movie doesn't exist". This is the temporal-coupling cost of
     * validating on the write path, which ties showtime creation to Catalog's uptime.
     */
    BOOKING_CATALOG_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "Catalog service unavailable"),

    /** A uniqueness constraint was violated (duplicate name, seat, or showtime slot). */
    BOOKING_DUPLICATE(HttpStatus.CONFLICT, "Duplicate resource"),

    /**
     * No (or an invalid) Bearer token. Rendered by the security filter chain — which runs before
     * any controller, so {@code GlobalExceptionHandler} can never see these — via
     * {@code SecurityProblemSupport}, in the same ProblemDetail shape with the same flat
     * {@code code}. Kept distinct from {@link #BOOKING_FORBIDDEN} on purpose: "log in" and "ask an
     * admin" are different answers.
     */
    BOOKING_UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "Authentication required"),

    /**
     * A valid token without the role the endpoint needs (e.g. a {@code USER} calling an
     * {@code ADMIN} inventory write). Same filter-chain rendering as {@link #BOOKING_UNAUTHORIZED}.
     */
    BOOKING_FORBIDDEN(HttpStatus.FORBIDDEN, "Access denied"),

    /**
     * Deleting a showtime would orphan existing bookings. Rejected by the FK (ON DELETE NO ACTION)
     * and pre-checked in the service so the caller gets a coded 409 instead of a raw DB error.
     */
    BOOKING_SHOWTIME_HAS_BOOKINGS(HttpStatus.CONFLICT, "Showtime has bookings"),

    /** A fallback for anything we did not anticipate — never leak internals to the caller. */
    BOOKING_INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "Internal error");

    private final HttpStatus status;
    private final String title;

    BookingErrorCode(HttpStatus status, String title) {
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
