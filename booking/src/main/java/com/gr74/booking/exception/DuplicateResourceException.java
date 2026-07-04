package com.gr74.booking.exception;

/**
 * Thrown when creating a resource would violate a uniqueness constraint — a theater name already
 * taken, a screen name reused within a theater, a seat position already placed, or a showtime slot
 * already booked. Maps to {@link BookingErrorCode#BOOKING_DUPLICATE} (HTTP 409 Conflict) via
 * {@link GlobalExceptionHandler}.
 *
 * <p>We raise this from a pre-check <em>and</em> as the translation of a database
 * {@code DataIntegrityViolationException} (the constraint is the real guard against a concurrent
 * duplicate the pre-check can't see) — same lesson as {@code payment}'s idempotency race.
 */
public class DuplicateResourceException extends BookingException {

    public DuplicateResourceException(String message) {
        super(BookingErrorCode.BOOKING_DUPLICATE, message);
    }

    public DuplicateResourceException(String message, Throwable cause) {
        super(BookingErrorCode.BOOKING_DUPLICATE, message, cause);
    }
}
