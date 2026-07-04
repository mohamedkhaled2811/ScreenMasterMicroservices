package com.gr74.booking.exception;

/**
 * Base class for every error the booking service raises on purpose.
 *
 * <p>Carrying a {@link BookingErrorCode} on the exception is what lets {@link GlobalExceptionHandler}
 * translate a thrown exception into the right HTTP status <em>and</em> the stable {@code code} the
 * caller branches on — without a chain of {@code instanceof} checks. Throw a subclass (or this class
 * directly) from the service layer; never let a raw {@link RuntimeException} escape with no code, or
 * the caller gets an opaque 500 it can't reason about. Mirrors {@code payment}'s {@code PaymentException}.
 */
public class BookingException extends RuntimeException {

    private final transient BookingErrorCode errorCode;

    public BookingException(BookingErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public BookingException(BookingErrorCode errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    public BookingErrorCode errorCode() {
        return errorCode;
    }
}
