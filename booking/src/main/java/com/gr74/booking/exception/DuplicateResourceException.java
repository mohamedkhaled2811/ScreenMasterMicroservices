package com.gr74.booking.exception;

/**
 * Uniqueness violation; maps to {@code BOOKING_DUPLICATE} (409).
 * Raised from pre-checks and as the translation of a DB constraint violation (concurrent duplicates).
 */
public class DuplicateResourceException extends BookingException {

    public DuplicateResourceException(String message) {
        super(BookingErrorCode.BOOKING_DUPLICATE, message);
    }

    public DuplicateResourceException(String message, Throwable cause) {
        super(BookingErrorCode.BOOKING_DUPLICATE, message, cause);
    }
}
