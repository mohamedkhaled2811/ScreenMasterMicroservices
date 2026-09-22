package com.gr74.booking.exception;

import org.springframework.http.HttpStatus;

/**
 * Machine-readable error codes; each pins its HTTP status so the two never drift apart.
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
     * No booking exists for the requested id; a definitive "no such booking", unlike an outage retry.
     */
    BOOKING_NOT_FOUND(HttpStatus.NOT_FOUND, "Booking not found"),

    /** A showtime referenced a {@code movieId} that Catalog does not have. */
    BOOKING_MOVIE_NOT_FOUND(HttpStatus.NOT_FOUND, "Movie not found in catalog"),

    /** Catalog could not be reached to validate the {@code movieId}; retryable, unlike a bad id. */
    BOOKING_CATALOG_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "Catalog service unavailable"),

    /** A uniqueness constraint was violated (duplicate name, seat, or showtime slot). */
    BOOKING_DUPLICATE(HttpStatus.CONFLICT, "Duplicate resource"),

    /** No (or an invalid) Bearer token; rendered by the filter chain in the same ProblemDetail shape. */
    BOOKING_UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "Authentication required"),

    /** Valid token without the needed role; same filter-chain rendering as unauthorized. */
    BOOKING_FORBIDDEN(HttpStatus.FORBIDDEN, "Access denied"),

    /** Deleting a showtime would orphan existing bookings; rejected with 409 instead of a raw DB error. */
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
